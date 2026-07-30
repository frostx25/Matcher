package com.matcher.app.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestRepositoryTest {
    @Test
    fun freeUserCanCreateFiveRequestsAndSixthIsRejected() {
        val repository = InMemoryChatRequestRepository(initialQuota = 5)

        repeat(5) { index ->
            val result = repository.createChatRequest(
                senderId = "user-free",
                recipientId = "user-target-${index + 1}",
                firstMessage = "Oi ${index + 1}",
            )

            assertTrue(result is ChatRequestResult.Created)
            assertEquals(4 - index, result.remainingQuota)
        }

        val rejected = repository.createChatRequest(
            senderId = "user-free",
            recipientId = "user-target-6",
            firstMessage = "Olá",
        )

        assertTrue(rejected is ChatRequestResult.QuotaExhausted)
        assertEquals(0, rejected.remainingQuota)
    }

    @Test
    fun existingRecipientDoesNotConsumeQuotaTwice() {
        val repository = InMemoryChatRequestRepository(initialQuota = 5)

        repository.createChatRequest("user-free", "user-target-01", "Oi")
        val result = repository.createChatRequest("user-free", "user-target-01", "Outra mensagem")

        assertTrue(result is ChatRequestResult.AlreadyExists)
        assertEquals(4, result.remainingQuota)
    }

    @Test
    fun blankMessageIsRejectedWithoutChangingQuota() {
        val repository = InMemoryChatRequestRepository(initialQuota = 5)

        val result = repository.createChatRequest("user-free", "user-target-01", "  ")

        assertTrue(result is ChatRequestResult.InvalidMessage)
        assertEquals(5, result.remainingQuota)
    }
}
