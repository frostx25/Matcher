package com.matcher.app.domain.chat

enum class ReportReason {
    Spam,
    Harassment,
    FakeProfile,
    Other,
}

enum class ChatMessageKind { Text, Photo }

enum class ChatDeliveryStatus { Sending, Sent, Delivered, Read, Failed }

enum class ChatMediaStatus { Pending, Approved, Adult, Abusive, Removed }

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String = "",
    val kind: ChatMessageKind = ChatMessageKind.Text,
    val deliveryStatus: ChatDeliveryStatus = ChatDeliveryStatus.Sent,
    val mediaStatus: ChatMediaStatus? = null,
    val clientMessageId: String? = null,
)

data class Conversation(
    val id: String,
    val participantIds: Set<String>,
    val startedByUserId: String,
    val messages: List<ChatMessage>,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
)

data class ModerationCase(
    val id: String,
    val reporterId: String,
    val reportedUserId: String,
    val reason: ReportReason,
    val details: String,
    val relatedConversationId: String?,
    val relatedMessageId: String? = null,
    val state: String = "pending-review",
)

data class ChatSnapshot(
    val remainingQuota: Int,
    val conversations: List<Conversation>,
    val blockedUserIds: Set<String>,
    val moderationCases: List<ModerationCase>,
)

sealed interface StartConversationResult {
    val remainingQuota: Int

    data class Created(
        val conversation: Conversation,
        override val remainingQuota: Int,
    ) : StartConversationResult

    data class Existing(
        val conversation: Conversation,
        override val remainingQuota: Int,
    ) : StartConversationResult

    data class QuotaExhausted(
        override val remainingQuota: Int,
    ) : StartConversationResult

    data class InvalidMessage(
        override val remainingQuota: Int,
    ) : StartConversationResult

    data class Blocked(
        override val remainingQuota: Int,
    ) : StartConversationResult
}

sealed interface SendMessageResult {
    data class Sent(val message: ChatMessage) : SendMessageResult
    data object InvalidMessage : SendMessageResult
    data object NotAllowed : SendMessageResult
    data object NotFound : SendMessageResult
}

interface ChatRepository {
    val remainingQuota: Int

    fun snapshot(currentUserId: String): ChatSnapshot

    fun startConversation(
        senderId: String,
        recipientId: String,
        firstMessage: String,
    ): StartConversationResult

    fun sendMessage(
        senderId: String,
        conversationId: String,
        body: String,
    ): SendMessageResult

    fun sendPhoto(
        senderId: String,
        conversationId: String,
        jpegBytes: ByteArray,
    ): SendMessageResult = SendMessageResult.NotAllowed

    fun blockUser(actorId: String, targetUserId: String): Boolean

    fun reportUser(
        actorId: String,
        targetUserId: String,
        reason: ReportReason,
        details: String,
        relatedMessageId: String? = null,
    ): ModerationCase?
}

/**
 * Local stand-in for the authoritative chat API. Starting a conversation,
 * writing its first message and consuming quota happen under the same lock.
 * Compose only submits intents and never owns quota or safety decisions.
 */
class InMemoryChatRepository(
    initialQuota: Int = 5,
    initialConversations: List<Conversation> = emptyList(),
) : ChatRepository {
    private data class BlockRelation(val blockerId: String, val blockedId: String)

    private val lock = Any()
    private var quota = initialQuota.coerceAtLeast(0)
    private var conversationSequence = initialConversations.size
    private var messageSequence = initialConversations.sumOf { it.messages.size }
    private var reportSequence = 0
    private val conversations = initialConversations.toMutableList()
    private val blocks = mutableSetOf<BlockRelation>()
    private val reports = mutableListOf<ModerationCase>()

    override val remainingQuota: Int
        get() = synchronized(lock) { quota }

    override fun snapshot(currentUserId: String): ChatSnapshot = synchronized(lock) {
        val blockedUserIds = blocks.mapNotNullTo(mutableSetOf()) { relation ->
            when (currentUserId) {
                relation.blockerId -> relation.blockedId
                relation.blockedId -> relation.blockerId
                else -> null
            }
        }
        ChatSnapshot(
            remainingQuota = quota,
            conversations = conversations.filter { currentUserId in it.participantIds },
            blockedUserIds = blockedUserIds,
            moderationCases = reports.filter { it.reporterId == currentUserId },
        )
    }

    override fun startConversation(
        senderId: String,
        recipientId: String,
        firstMessage: String,
    ): StartConversationResult = synchronized(lock) {
        if (!isValidMessage(firstMessage)) {
            return@synchronized StartConversationResult.InvalidMessage(quota)
        }
        if (senderId == recipientId || isBlockedPair(senderId, recipientId)) {
            return@synchronized StartConversationResult.Blocked(quota)
        }

        val existingIndex = conversations.indexOfFirst {
            senderId in it.participantIds && recipientId in it.participantIds
        }
        if (existingIndex >= 0) {
            val updated = appendMessage(existingIndex, senderId, firstMessage)
            return@synchronized StartConversationResult.Existing(updated, quota)
        }

        if (quota == 0) {
            return@synchronized StartConversationResult.QuotaExhausted(quota)
        }

        conversationSequence += 1
        val conversationId = "conversation-$conversationSequence"
        messageSequence += 1
        val conversation = Conversation(
            id = conversationId,
            participantIds = setOf(senderId, recipientId),
            startedByUserId = senderId,
            messages = listOf(
                ChatMessage(
                    id = "message-$messageSequence",
                    conversationId = conversationId,
                    senderId = senderId,
                    body = firstMessage.trim(),
                ),
            ),
        )
        conversations += conversation
        quota -= 1
        StartConversationResult.Created(conversation, quota)
    }

    override fun sendMessage(
        senderId: String,
        conversationId: String,
        body: String,
    ): SendMessageResult = synchronized(lock) {
        if (!isValidMessage(body)) return@synchronized SendMessageResult.InvalidMessage

        val conversationIndex = conversations.indexOfFirst { it.id == conversationId }
        if (conversationIndex == -1) return@synchronized SendMessageResult.NotFound

        val conversation = conversations[conversationIndex]
        if (senderId !in conversation.participantIds) {
            return@synchronized SendMessageResult.NotAllowed
        }
        val otherUserId = conversation.participantIds.first { it != senderId }
        if (isBlockedPair(senderId, otherUserId)) {
            return@synchronized SendMessageResult.NotAllowed
        }

        val updated = appendMessage(conversationIndex, senderId, body)
        SendMessageResult.Sent(updated.messages.last())
    }

    override fun sendPhoto(
        senderId: String,
        conversationId: String,
        jpegBytes: ByteArray,
    ): SendMessageResult = synchronized(lock) {
        if (jpegBytes.isEmpty()) return@synchronized SendMessageResult.InvalidMessage
        val conversationIndex = conversations.indexOfFirst { it.id == conversationId }
        if (conversationIndex == -1) return@synchronized SendMessageResult.NotFound
        val conversation = conversations[conversationIndex]
        if (senderId !in conversation.participantIds) return@synchronized SendMessageResult.NotAllowed
        val otherUserId = conversation.participantIds.first { it != senderId }
        if (isBlockedPair(senderId, otherUserId)) return@synchronized SendMessageResult.NotAllowed
        messageSequence += 1
        val message = ChatMessage(
            id = "message-$messageSequence",
            conversationId = conversationId,
            senderId = senderId,
            kind = ChatMessageKind.Photo,
            mediaStatus = ChatMediaStatus.Pending,
        )
        conversations[conversationIndex] = conversation.copy(messages = conversation.messages + message)
        SendMessageResult.Sent(message)
    }

    override fun blockUser(actorId: String, targetUserId: String): Boolean = synchronized(lock) {
        if (actorId == targetUserId) return@synchronized false
        blockPair(actorId, targetUserId)
        true
    }

    override fun reportUser(
        actorId: String,
        targetUserId: String,
        reason: ReportReason,
        details: String,
        relatedMessageId: String?,
    ): ModerationCase? = synchronized(lock) {
        if (actorId == targetUserId) return@synchronized null
        val relatedConversationId = conversations.firstOrNull {
            actorId in it.participantIds && targetUserId in it.participantIds
        }?.id
        val moderationCase = createReport(
            actorId = actorId,
            targetUserId = targetUserId,
            reason = reason,
            details = details,
            relatedConversationId = relatedConversationId,
            relatedMessageId = relatedMessageId,
        )
        blockPair(actorId, targetUserId)
        moderationCase
    }

    private fun appendMessage(
        conversationIndex: Int,
        senderId: String,
        body: String,
    ): Conversation {
        val conversation = conversations[conversationIndex]
        messageSequence += 1
        val message = ChatMessage(
            id = "message-$messageSequence",
            conversationId = conversation.id,
            senderId = senderId,
            body = body.trim(),
        )
        return conversation.copy(messages = conversation.messages + message).also {
            conversations[conversationIndex] = it
        }
    }

    private fun blockPair(blockerId: String, blockedId: String) {
        blocks += BlockRelation(blockerId, blockedId)
        conversations.removeAll { conversation ->
            blockerId in conversation.participantIds && blockedId in conversation.participantIds
        }
    }

    private fun createReport(
        actorId: String,
        targetUserId: String,
        reason: ReportReason,
        details: String,
        relatedConversationId: String?,
        relatedMessageId: String?,
    ): ModerationCase {
        reportSequence += 1
        return ModerationCase(
            id = "moderation-case-$reportSequence",
            reporterId = actorId,
            reportedUserId = targetUserId,
            reason = reason,
            details = details.trim(),
            relatedConversationId = relatedConversationId,
            relatedMessageId = relatedMessageId,
        ).also(reports::add)
    }

    private fun isBlockedPair(firstUserId: String, secondUserId: String): Boolean {
        return blocks.any { relation ->
            (relation.blockerId == firstUserId && relation.blockedId == secondUserId) ||
                (relation.blockerId == secondUserId && relation.blockedId == firstUserId)
        }
    }

    private fun isValidMessage(message: String): Boolean {
        return message.isNotBlank() && message.trim().length <= MaxMessageLength
    }

    private companion object {
        const val MaxMessageLength = 2_000
    }
}
