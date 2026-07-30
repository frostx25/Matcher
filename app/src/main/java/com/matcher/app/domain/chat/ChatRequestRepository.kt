package com.matcher.app.domain.chat

data class ChatRequest(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val firstMessage: String,
    val status: ChatRequestStatus = ChatRequestStatus.Pending,
)

enum class ChatRequestStatus {
    Pending,
    Active,
    Ignored,
    Blocked,
}

sealed interface ChatRequestResult {
    val remainingQuota: Int

    data class Created(
        val request: ChatRequest,
        override val remainingQuota: Int,
    ) : ChatRequestResult

    data class AlreadyExists(
        val request: ChatRequest,
        override val remainingQuota: Int,
    ) : ChatRequestResult

    data class QuotaExhausted(
        override val remainingQuota: Int,
    ) : ChatRequestResult

    data class InvalidMessage(
        override val remainingQuota: Int,
    ) : ChatRequestResult
}

interface ChatRequestRepository {
    val remainingQuota: Int

    fun createChatRequest(
        senderId: String,
        recipientId: String,
        firstMessage: String,
    ): ChatRequestResult
}

/**
 * Local stand-in for the authoritative chat API. It is intentionally small and
 * replaceable so the UI does not own quota decisions when the backend arrives.
 */
class InMemoryChatRequestRepository(
    initialQuota: Int = 5,
) : ChatRequestRepository {
    private val lock = Any()
    private var quota = initialQuota.coerceAtLeast(0)
    private val requests = mutableListOf<ChatRequest>()

    override val remainingQuota: Int
        get() = synchronized(lock) { quota }

    override fun createChatRequest(
        senderId: String,
        recipientId: String,
        firstMessage: String,
    ): ChatRequestResult = synchronized(lock) {
        if (firstMessage.isBlank()) {
            return@synchronized ChatRequestResult.InvalidMessage(quota)
        }

        val existing = requests.firstOrNull {
            it.senderId == senderId && it.recipientId == recipientId
        }
        if (existing != null) {
            return@synchronized ChatRequestResult.AlreadyExists(existing, quota)
        }

        if (quota == 0) {
            return@synchronized ChatRequestResult.QuotaExhausted(quota)
        }

        val request = ChatRequest(
            id = "chat-request-${requests.size + 1}",
            senderId = senderId,
            recipientId = recipientId,
            firstMessage = firstMessage.trim(),
        )
        requests += request
        quota -= 1
        ChatRequestResult.Created(request, quota)
    }
}
