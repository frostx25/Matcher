package shop.vibeali.app.data.remote

import shop.vibeali.app.domain.chat.ChatMessage
import shop.vibeali.app.domain.chat.ChatDeliveryStatus
import shop.vibeali.app.domain.chat.ChatMediaStatus
import shop.vibeali.app.domain.chat.ChatMessageKind
import shop.vibeali.app.domain.chat.ChatSnapshot
import shop.vibeali.app.domain.chat.Conversation
import shop.vibeali.app.domain.chat.ModerationCase
import shop.vibeali.app.domain.chat.ReportReason
import shop.vibeali.app.domain.chat.SendMessageResult
import shop.vibeali.app.domain.chat.StartConversationResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.util.UUID
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
private data class SendPhotoRow(
    @SerialName("message_id") val messageId: String,
    @SerialName("moderation_status") val moderationStatus: String,
)

@Serializable
private data class AuthorizedChatMediaRow(
    @SerialName("object_path") val objectPath: String,
    @SerialName("mime_type") val mimeType: String,
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
    val body: String? = null,
    val kind: String = "text",
    @SerialName("client_message_id") val clientMessageId: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("media_status") val mediaStatus: String? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("reply_preview") val replyPreview: String? = null,
    @SerialName("reaction_count") val reactionCount: Int = 0,
    @SerialName("reacted_by_me") val reactedByMe: Boolean = false,
    @SerialName("album_event") val albumEvent: String? = null,
    @SerialName("album_id") val albumId: String? = null,
)

@Serializable
private data class ConversationUserStateRow(
    @SerialName("conversation_id") val conversationId: String,
    val muted: Boolean,
    @SerialName("unread_count") val unreadCount: Int,
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
    @SerialName("related_message_id") val relatedMessageId: String? = null,
)

interface RemoteChatGateway {
    suspend fun snapshot(): ChatSnapshot

    suspend fun startConversation(recipientId: String, firstMessage: String): StartConversationResult

    suspend fun sendMessage(conversationId: String, body: String): SendMessageResult

    suspend fun sendMessageWithKey(
        conversationId: String,
        body: String,
        clientMessageId: String,
    ): SendMessageResult = sendMessage(conversationId, body)

    suspend fun sendMessageWithReplyKey(
        conversationId: String,
        body: String,
        clientMessageId: String,
        replyToMessageId: String?,
    ): SendMessageResult = sendMessageWithKey(conversationId, body, clientMessageId)

    suspend fun toggleMessageReaction(messageId: String): Boolean = false

    suspend fun sendPhoto(
        conversationId: String,
        jpegBytes: ByteArray,
        clientMessageId: String,
    ): SendMessageResult = SendMessageResult.NotAllowed

    suspend fun markConversationRead(conversationId: String): Boolean = false

    suspend fun setConversationMuted(conversationId: String, muted: Boolean): Boolean = false

    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Boolean = false

    suspend fun setConversationDeleted(conversationId: String, deleted: Boolean): Boolean = false

    suspend fun setConversationTyping(conversationId: String, typing: Boolean): Boolean = false

    suspend fun downloadChatPhoto(messageId: String): ByteArray = error("CHAT_PHOTO_NOT_AVAILABLE")

    suspend fun blockUser(targetUserId: String): Boolean

    suspend fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
    ): ModerationCase

    suspend fun reportMessage(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String,
        messageId: String,
    ): ModerationCase = reportUser(targetUserId, reason, details, conversationId)

    fun realtimeInvalidations(): Flow<Unit>
}

class SupabaseChatGateway(
    private val client: SupabaseClient,
) : RemoteChatGateway {
    override suspend fun snapshot(): ChatSnapshot {
        val currentUserId = currentUserId()
        client.postgrest.rpc("mark_chat_delivered")
        val quota = remainingQuota()
        val states = client.postgrest.rpc("get_chat_user_states")
            .decodeList<ConversationUserStateRow>()
            .associateBy { it.conversationId }
        val archivedIds = client.postgrest.rpc("list_archived_conversation_ids")
            .decodeList<String>().toSet()
        val deletedIds = client.postgrest.rpc("list_deleted_conversation_ids")
            .decodeList<String>().toSet()
        val typingIds = client.postgrest.rpc("list_typing_conversation_ids")
            .decodeList<String>().toSet()
        val conversations = client.from("conversations").select {
            order("last_message_at", Order.DESCENDING)
            range(0L..49L)
        }.decodeList<ConversationRow>().filterNot { it.id in deletedIds }.map { row ->
            val messages = loadMessages(row.id)
            row.toDomain(
                messages = messages,
                unreadCount = states[row.id]?.unreadCount ?: 0,
                muted = states[row.id]?.muted ?: false,
                archived = row.id in archivedIds,
                participantTyping = row.id in typingIds,
            )
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
        return sendMessageWithKey(conversationId, body, UUID.randomUUID().toString())
    }

    override suspend fun sendMessageWithKey(
        conversationId: String,
        body: String,
        clientMessageId: String,
    ): SendMessageResult = sendMessageWithReplyKey(conversationId, body, clientMessageId, null)

    override suspend fun sendMessageWithReplyKey(
        conversationId: String,
        body: String,
        clientMessageId: String,
        replyToMessageId: String?,
    ): SendMessageResult {
        return try {
            val messageId = client.postgrest.rpc(
                function = "send_message",
                parameters = buildJsonObject {
                    put("conversation_id", conversationId)
                    put("message_body", body)
                    put("client_message_id", clientMessageId)
                    if (replyToMessageId == null) put("reply_to_message_id", kotlinx.serialization.json.JsonNull)
                    else put("reply_to_message_id", replyToMessageId)
                },
            ).decodeAs<String>()
            SendMessageResult.Sent(
                ChatMessage(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = currentUserId(),
                    body = body.trim(),
                    deliveryStatus = ChatDeliveryStatus.Sent,
                    clientMessageId = clientMessageId,
                    replyToMessageId = replyToMessageId,
                ),
            )
        } catch (error: PostgrestRestException) {
            when (error.matcherCode()) {
                "INVALID_MESSAGE" -> SendMessageResult.InvalidMessage
                "MESSAGE_RATE_LIMITED", "REPEATED_MESSAGE_LIMITED" -> SendMessageResult.RateLimited
                "CHAT_NOT_AVAILABLE" -> SendMessageResult.NotAllowed
                "MESSAGE_RATE_LIMITED", "REPEATED_MESSAGE_LIMITED" -> SendMessageResult.RateLimited
                else -> throw error
            }
        }
    }

    override suspend fun toggleMessageReaction(messageId: String): Boolean = client.postgrest.rpc(
        function = "toggle_message_reaction",
        parameters = buildJsonObject { put("target_message_id", messageId) },
    ).decodeAs<Boolean>()

    override suspend fun sendPhoto(
        conversationId: String,
        jpegBytes: ByteArray,
        clientMessageId: String,
    ): SendMessageResult {
        if (jpegBytes.size !in 1..MAX_CHAT_PHOTO_BYTES || !jpegBytes.isJpeg()) {
            return SendMessageResult.InvalidMessage
        }
        val normalizedKey = runCatching { UUID.fromString(clientMessageId).toString() }.getOrNull()
            ?: return SendMessageResult.InvalidMessage
        val objectPath = "${currentUserId()}/$conversationId/$normalizedKey.jpg"
        val bucket = client.storage.from(CHAT_MEDIA_BUCKET)
        runCatching {
            bucket.upload(objectPath, jpegBytes) {
                upsert = false
                contentType = ContentType.Image.JPEG
            }
        }
        return try {
            val row = client.postgrest.rpc(
                function = "send_photo_message",
                parameters = buildJsonObject {
                    put("conversation_id", conversationId)
                    put("client_message_id", normalizedKey)
                    put("object_path", objectPath)
                    put("media_type", "image/jpeg")
                },
            ).decodeSingle<SendPhotoRow>()
            SendMessageResult.Sent(
                ChatMessage(
                    id = row.messageId,
                    conversationId = conversationId,
                    senderId = currentUserId(),
                    kind = ChatMessageKind.Photo,
                    mediaStatus = row.moderationStatus.toMediaStatus(),
                    deliveryStatus = ChatDeliveryStatus.Sent,
                    clientMessageId = normalizedKey,
                ),
            )
        } catch (error: PostgrestRestException) {
            when (error.matcherCode()) {
                "CHAT_NOT_AVAILABLE" -> SendMessageResult.NotAllowed
                "INVALID_CHAT_PHOTO", "CHAT_PHOTO_NOT_FOUND" -> SendMessageResult.InvalidMessage
                else -> throw error
            }
        }
    }

    override suspend fun markConversationRead(conversationId: String): Boolean {
        client.postgrest.rpc(
            function = "mark_conversation_read",
            parameters = buildJsonObject { put("target_conversation_id", conversationId) },
        ).decodeAs<Int>()
        return true
    }

    override suspend fun setConversationMuted(conversationId: String, muted: Boolean): Boolean =
        client.postgrest.rpc(
            function = "set_conversation_muted",
            parameters = buildJsonObject {
                put("target_conversation_id", conversationId)
                put("should_mute", muted)
            },
        ).decodeAs()

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Boolean =
        client.postgrest.rpc(
            function = "set_conversation_archived",
            parameters = buildJsonObject {
                put("target_conversation_id", conversationId)
                put("archived", archived)
            },
        ).decodeAs()

    override suspend fun setConversationDeleted(conversationId: String, deleted: Boolean): Boolean =
        client.postgrest.rpc(
            function = "set_conversation_deleted",
            parameters = buildJsonObject {
                put("target_conversation_id", conversationId)
                put("deleted", deleted)
            },
        ).decodeAs()

    override suspend fun setConversationTyping(conversationId: String, typing: Boolean): Boolean =
        client.postgrest.rpc(
            function = "set_conversation_typing",
            parameters = buildJsonObject {
                put("target_conversation_id", conversationId)
                put("typing", typing)
            },
        ).decodeAs()

    override suspend fun downloadChatPhoto(messageId: String): ByteArray {
        val media = client.postgrest.rpc(
            function = "authorize_chat_media",
            parameters = buildJsonObject { put("target_message_id", messageId) },
        ).decodeList<AuthorizedChatMediaRow>().singleOrNull()
            ?: error("CHAT_PHOTO_NOT_AVAILABLE")
        check(media.mimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "INVALID_CHAT_PHOTO"
        }
        val bytes = client.storage.from(CHAT_MEDIA_BUCKET).downloadAuthenticated(media.objectPath)
        check(bytes.size in 1..MAX_CHAT_PHOTO_BYTES) { "INVALID_CHAT_PHOTO" }
        return bytes
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
    ): ModerationCase = reportContent(targetUserId, reason, details, conversationId, null)

    override suspend fun reportMessage(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String,
        messageId: String,
    ): ModerationCase = reportContent(targetUserId, reason, details, conversationId, messageId)

    private suspend fun reportContent(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
        messageId: String?,
    ): ModerationCase {
        val caseId = client.postgrest.rpc(
            function = "report_user",
            parameters = buildJsonObject {
                put("reported_user_id", targetUserId)
                put("report_reason", reason.toRemoteValue())
                put("report_details", details)
                put("related_conversation_id", conversationId)
                put("related_message_id", messageId)
            },
        ).decodeAs<String>()
        return ModerationCase(
            id = caseId,
            reporterId = currentUserId(),
            reportedUserId = targetUserId,
            reason = reason,
            details = details.trim(),
            relatedConversationId = conversationId,
            relatedMessageId = messageId,
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
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "conversation_user_states"
            },
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "conversation_typing_states"
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
        val messages = loadMessages(row.id)
        val currentUserId = currentUserId()
        return row.toDomain(
            messages,
            unreadCount = messages.count { it.senderId != currentUserId && it.deliveryStatus != ChatDeliveryStatus.Read },
        )
    }

    private suspend fun loadMessages(conversationId: String): List<ChatMessage> =
        client.postgrest.rpc(
            function = "list_chat_messages",
            parameters = buildJsonObject { put("target_conversation_id", conversationId) },
        ).decodeList<MessageRow>().map(MessageRow::toDomain)

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
        "MESSAGE_RATE_LIMITED",
        "REPEATED_MESSAGE_LIMITED",
        "CHAT_BLOCKED",
        "CHAT_QUOTA_EXHAUSTED",
        "CHAT_NOT_AVAILABLE",
        "INVALID_CLIENT_MESSAGE_ID",
        "CLIENT_MESSAGE_CONFLICT",
        "INVALID_CHAT_PHOTO",
        "CHAT_PHOTO_NOT_FOUND",
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
        "PRIVATE_ALBUM_CHANGED",
        "PRIVATE_ALBUM_LIMIT_REACHED",
        "PRIVATE_ALBUM_ITEM_NOT_FOUND",
        "PRIVATE_ALBUM_ITEM_NOT_FINALIZABLE",
        "PRIVATE_ALBUM_OBJECT_NOT_FOUND",
        "PRIVATE_ALBUM_FORBIDDEN",
        "PRIVATE_ALBUM_ACCESS_DENIED",
        "PRIVATE_ALBUM_STORAGE_ACCESS_DENIED",
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
        "INVALID_PRIVATE_ALBUM_RESPONSE",
        "INVALID_ALBUM_OWNER",
        "INVALID_ALBUM_RECIPIENT",
        "ALBUM_BLOCKED",
    )
    for (error in selfAndCauses()) {
        knownCodes.firstOrNull { error.message?.contains(it) == true }?.let { return it }
    }
    return null
}

internal fun Throwable.selfAndCauses(): Sequence<Throwable> =
    generateSequence(this) { current -> current.cause }.take(16)

private fun ConversationRow.toDomain(
    messages: List<ChatMessage>,
    unreadCount: Int = 0,
    muted: Boolean = false,
    archived: Boolean = false,
    participantTyping: Boolean = false,
) = Conversation(
    id = id,
    participantIds = setOf(participantA, participantB),
    startedByUserId = startedBy,
    messages = messages,
    unreadCount = unreadCount,
    muted = muted,
    archived = archived,
    participantTyping = participantTyping,
)

private fun MessageRow.toDomain() = ChatMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    body = body.orEmpty(),
    kind = if (kind == "photo") ChatMessageKind.Photo else ChatMessageKind.Text,
    deliveryStatus = when {
        readAt != null -> ChatDeliveryStatus.Read
        deliveredAt != null -> ChatDeliveryStatus.Delivered
        else -> ChatDeliveryStatus.Sent
    },
    mediaStatus = mediaStatus?.toMediaStatus(),
    clientMessageId = clientMessageId,
    replyToMessageId = replyToMessageId,
    replyPreview = replyPreview,
    reactionCount = reactionCount,
    reactedByMe = reactedByMe,
    albumEvent = albumEvent,
    albumId = albumId,
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
    relatedMessageId = relatedMessageId,
)

private fun String.toMediaStatus(): ChatMediaStatus = when (this) {
    "approved" -> ChatMediaStatus.Approved
    "adult" -> ChatMediaStatus.Adult
    "abusive" -> ChatMediaStatus.Abusive
    "removed" -> ChatMediaStatus.Removed
    else -> ChatMediaStatus.Pending
}

private fun ByteArray.isJpeg(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() && this[size - 1] == 0xD9.toByte()

private const val CHAT_MEDIA_BUCKET = "chat-media"
private const val MAX_CHAT_PHOTO_BYTES = 5 * 1024 * 1024

private fun ReportReason.toRemoteValue(): String = when (this) {
    ReportReason.Spam -> "spam"
    ReportReason.Harassment -> "harassment"
    ReportReason.FakeProfile -> "fake_profile"
    ReportReason.Other -> "other"
}
