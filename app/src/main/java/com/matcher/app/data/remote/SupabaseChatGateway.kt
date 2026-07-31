package com.matcher.app.data.remote

import com.matcher.app.domain.chat.ChatMessage
import com.matcher.app.domain.chat.ChatSnapshot
import com.matcher.app.domain.chat.Conversation
import com.matcher.app.domain.chat.ModerationCase
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.domain.chat.SendMessageResult
import com.matcher.app.domain.chat.StartConversationResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class QuotaRow(
    @SerialName("remaining_count") val remainingCount: Int,
)

@Serializable
private data class StartConversationRequest(
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("first_message") val firstMessage: String,
)

@Serializable
private data class StartConversationRow(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("was_created") val wasCreated: Boolean,
    @SerialName("remaining_quota") val remainingQuota: Int,
)

@Serializable
private data class SendMessageRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_body") val messageBody: String,
)

@Serializable
private data class BlockUserRequest(
    @SerialName("blocked_user_id") val blockedUserId: String,
)

@Serializable
private data class ReportUserRequest(
    @SerialName("reported_user_id") val reportedUserId: String,
    @SerialName("report_reason") val reportReason: String,
    @SerialName("report_details") val reportDetails: String,
    @SerialName("related_conversation_id") val relatedConversationId: String?,
    @SerialName("related_message_id") val relatedMessageId: String? = null,
)

@Serializable
private data class ConversationRow(
    val id: String,
    @SerialName("participant_a") val participantA: String,
    @SerialName("participant_b") val participantB: String,
    @SerialName("started_by") val startedBy: String,
)

@Serializable
private data class MessageRow(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val body: String,
)

@Serializable
private data class BlockRow(
    @SerialName("blocked_id") val blockedId: String,
)

@Serializable
private data class ReportRow(
    val id: String,
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("reported_user_id") val reportedUserId: String,
    val reason: String,
    val details: String,
    @SerialName("conversation_id") val conversationId: String?,
)

interface RemoteChatGateway {
    suspend fun snapshot(): ChatSnapshot

    suspend fun startConversation(recipientId: String, firstMessage: String): StartConversationResult

    suspend fun sendMessage(conversationId: String, body: String): SendMessageResult

    suspend fun blockUser(targetUserId: String): Boolean

    suspend fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
    ): ModerationCase

    fun realtimeInvalidations(): Flow<Unit>
}

class SupabaseChatGateway(
    private val client: SupabaseClient,
) : RemoteChatGateway {
    override suspend fun snapshot(): ChatSnapshot {
        val currentUserId = currentUserId()
        val quota = remainingQuota()
        val conversations = client.from("conversations").select {
            order("last_message_at", Order.DESCENDING)
            range(0L..49L)
        }.decodeList<ConversationRow>().map { row ->
            row.toDomain(loadMessages(row.id))
        }
        val blocks = client.from("blocks").select {
            range(0L..199L)
        }.decodeList<BlockRow>()
        val reports = client.from("reports").select {
            order("created_at", Order.DESCENDING)
            range(0L..49L)
        }.decodeList<ReportRow>()
        return ChatSnapshot(
            remainingQuota = quota,
            conversations = conversations,
            blockedUserIds = blocks.mapTo(mutableSetOf()) { it.blockedId },
            moderationCases = reports.map { it.toDomain() }.filter { it.reporterId == currentUserId },
        )
    }

    override suspend fun startConversation(
        recipientId: String,
        firstMessage: String,
    ): StartConversationResult {
        val quotaBefore = remainingQuota()
        return try {
            val result = client.postgrest.rpc(
                function = "start_conversation",
                parameters = buildJsonObject {
                    put("recipient_id", recipientId)
                    put("first_message", firstMessage)
                },
            ).decodeSingle<StartConversationRow>()
            val conversation = loadConversation(result.conversationId)
            if (result.wasCreated) {
                StartConversationResult.Created(conversation, result.remainingQuota)
            } else {
                StartConversationResult.Existing(conversation, result.remainingQuota)
            }
        } catch (error: PostgrestRestException) {
            when (error.matcherCode()) {
                "CHAT_QUOTA_EXHAUSTED" -> StartConversationResult.QuotaExhausted(quotaBefore)
                "INVALID_MESSAGE" -> StartConversationResult.InvalidMessage(quotaBefore)
                "CHAT_BLOCKED", "INVALID_RECIPIENT", "RECIPIENT_NOT_AVAILABLE" ->
                    StartConversationResult.Blocked(quotaBefore)
                else -> throw error
            }
        }
    }

    override suspend fun sendMessage(conversationId: String, body: String): SendMessageResult {
        return try {
            val messageId = client.postgrest.rpc(
                function = "send_message",
                parameters = buildJsonObject {
                    put("conversation_id", conversationId)
                    put("message_body", body)
                },
            ).decodeAs<String>()
            SendMessageResult.Sent(
                ChatMessage(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = currentUserId(),
                    body = body.trim(),
                ),
            )
        } catch (error: PostgrestRestException) {
            when (error.matcherCode()) {
                "INVALID_MESSAGE" -> SendMessageResult.InvalidMessage
                "CHAT_NOT_AVAILABLE" -> SendMessageResult.NotAllowed
                else -> throw error
            }
        }
    }

    override suspend fun blockUser(targetUserId: String): Boolean = client.postgrest.rpc(
        function = "block_user",
        parameters = buildJsonObject { put("blocked_user_id", targetUserId) },
    ).decodeAs()

    override suspend fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
    ): ModerationCase {
        val caseId = client.postgrest.rpc(
            function = "report_user",
            parameters = buildJsonObject {
                put("reported_user_id", targetUserId)
                put("report_reason", reason.toRemoteValue())
                put("report_details", details)
                put("related_conversation_id", conversationId)
                put("related_message_id", null as String?)
            },
        ).decodeAs<String>()
        return ModerationCase(
            id = caseId,
            reporterId = currentUserId(),
            reportedUserId = targetUserId,
            reason = reason,
            details = details.trim(),
            relatedConversationId = conversationId,
        )
    }

    override fun realtimeInvalidations(): Flow<Unit> = callbackFlow {
        val channel = client.channel("matcher-chat-${currentUserId()}")
        val changes = merge(
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "messages"
            },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "conversations"
            },
        ).onEach { trySend(Unit) }.launchIn(this)
        channel.subscribe(blockUntilSubscribed = true)
        awaitClose {
            changes.cancel()
            CoroutineScope(Dispatchers.IO).launch { channel.unsubscribe() }
        }
    }

    private suspend fun remainingQuota(): Int = client.postgrest
        .rpc("get_chat_quota")
        .decodeSingle<QuotaRow>()
        .remainingCount

    private suspend fun loadConversation(conversationId: String): Conversation {
        val row = client.from("conversations").select {
            filter { eq("id", conversationId) }
        }.decodeSingle<ConversationRow>()
        return row.toDomain(loadMessages(row.id))
    }

    private suspend fun loadMessages(conversationId: String): List<ChatMessage> =
        client.from("messages").select {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.ASCENDING)
            range(0L..199L)
        }.decodeList<MessageRow>().map(MessageRow::toDomain)

    private fun currentUserId(): String =
        requireNotNull(client.auth.currentUserOrNull()) { "Authenticated session required" }.id
}

internal fun Throwable.matcherCode(): String? {
    val knownCodes = listOf(
        "AUTH_REQUIRED",
        "ACCOUNT_NOT_ACTIVE",
        "RECIPIENT_NOT_AVAILABLE",
        "INVALID_RECIPIENT",
        "INVALID_MESSAGE",
        "CHAT_BLOCKED",
        "CHAT_QUOTA_EXHAUSTED",
        "CHAT_NOT_AVAILABLE",
        "ADULTS_ONLY",
        "TERMS_REQUIRED",
        "BIRTH_YEAR_LOCKED",
        "ONBOARDING_REQUIRED",
        "ALREADY_VERIFIED",
        "AGE_SESSION_RATE_LIMITED",
        "AGE_SESSION_IN_PROGRESS",
        "AGE_SESSION_CREATE_FAILED",
        "AGE_PROVIDER_NOT_CONFIGURED",
        "AGE_PROVIDER_UNAVAILABLE",
        "AGE_PROVIDER_INVALID_RESPONSE",
        "AGE_PROVIDER_INVALID_URL",
        "AGE_REVIEW_PENDING",
        "ACCOUNT_SUSPENDED",
        "ACCOUNT_DELETED",
        "ACCOUNT_UNAVAILABLE",
        "BACKEND_NOT_CONFIGURED",
        "INVALID_PROFILE_PHOTO_SIZE",
        "PROFILE_PHOTO_TOO_LARGE",
        "INVALID_PROFILE_PHOTO_FORMAT",
        "PROFILE_PHOTO_INVALID",
        "INVALID_PROFILE_PHOTO_METADATA",
        "INVALID_PROFILE_PHOTO_PATH",
        "PROFILE_PHOTO_NOT_FOUND",
        "PROFILE_NOT_FOUND",
        "INVALID_GENDER_IDENTITY",
        "INVALID_GENDER_SELF_DESCRIPTION",
        "INVALID_GENDER_VISIBILITY",
        "INVALID_DISCOVERY_PREFERENCE",
        "DISCOVERY_CURSOR_STALE",
        "CONTENT_POLICY_REQUIRED",
        "INVALID_CONTENT_POLICY_VERSION",
        "PRIVATE_ALBUM_NOT_FOUND",
        "PRIVATE_ALBUM_NOT_AVAILABLE",
        "PRIVATE_ALBUM_LIMIT_REACHED",
        "PRIVATE_ALBUM_ITEM_NOT_FOUND",
        "PRIVATE_ALBUM_ITEM_NOT_FINALIZABLE",
        "PRIVATE_ALBUM_OBJECT_NOT_FOUND",
        "PRIVATE_ALBUM_FORBIDDEN",
        "PRIVATE_ALBUM_ACCESS_DENIED",
        "ALBUM_ACCESS_DENIED",
        "ALBUM_ACCESS_BLOCKED",
        "PRIVATE_ALBUM_EMPTY",
        "PRIVATE_ALBUM_DELETE_PENDING",
        "INVALID_PRIVATE_ALBUM_IMAGE_SIZE",
        "INVALID_PRIVATE_ALBUM_IMAGE_FORMAT",
        "INVALID_PRIVATE_ALBUM_MEDIA_TYPE",
        "INVALID_PRIVATE_ALBUM_MEDIA_RESPONSE",
        "INVALID_PRIVATE_ALBUM_CACHE_POLICY",
        "INVALID_PRIVATE_ALBUM_ITEM",
        "INVALID_PRIVATE_ALBUM_PATH",
        "INVALID_ALBUM_OWNER",
        "INVALID_ALBUM_RECIPIENT",
        "ALBUM_BLOCKED",
    )
    return knownCodes.firstOrNull { message?.contains(it) == true }
}

private fun ConversationRow.toDomain(messages: List<ChatMessage>) = Conversation(
    id = id,
    participantIds = setOf(participantA, participantB),
    startedByUserId = startedBy,
    messages = messages,
)

private fun MessageRow.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body,
)

private fun ReportRow.toDomain() = ModerationCase(
    id = id,
    reporterId = reporterId,
    reportedUserId = reportedUserId,
    reason = when (reason) {
        "spam" -> ReportReason.Spam
        "harassment" -> ReportReason.Harassment
        "fake_profile" -> ReportReason.FakeProfile
        else -> ReportReason.Other
    },
    details = details,
    relatedConversationId = conversationId,
)

private fun ReportReason.toRemoteValue(): String = when (this) {
    ReportReason.Spam -> "spam"
    ReportReason.Harassment -> "harassment"
    ReportReason.FakeProfile -> "fake_profile"
    ReportReason.Other -> "other"
}
