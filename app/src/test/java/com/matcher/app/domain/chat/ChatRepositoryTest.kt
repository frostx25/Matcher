package com.matcher.app.domain.chat

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    @Test
    fun freeUserCanOpenFiveConversationsAndSixthIsRejected() {
        val repository = InMemoryChatRepository(initialQuota = 5)

        repeat(5) { index ->
            val result = repository.startConversation(
                senderId = "user-free",
                recipientId = "user-target-${index + 1}",
                firstMessage = "Oi ${index + 1}",
            )

            assertTrue(result is StartConversationResult.Created)
            assertEquals(4 - index, result.remainingQuota)
        }

        val rejected = repository.startConversation("user-free", "user-target-6", "Olá")

        assertTrue(rejected is StartConversationResult.QuotaExhausted)
        assertEquals(0, rejected.remainingQuota)
    }

    @Test
    fun firstMessageCreatesActiveConversationForBothUsersImmediately() {
        val repository = InMemoryChatRepository(initialQuota = 5)

        val result = repository.startConversation("user-free", "maya", "Oi, tudo bem?")

        assertTrue(result is StartConversationResult.Created)
        val senderConversation = repository.snapshot("user-free").conversations.single()
        val recipientConversation = repository.snapshot("maya").conversations.single()
        assertEquals(senderConversation, recipientConversation)
        assertEquals("Oi, tudo bem?", senderConversation.messages.single().body)
        assertEquals("user-free", senderConversation.startedByUserId)
    }

    @Test
    fun existingPairUsesSameConversationWithoutConsumingQuotaTwice() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        val created = repository.startConversation("user-free", "maya", "Oi") as StartConversationResult.Created

        val result = repository.startConversation("user-free", "maya", "Outra mensagem")

        assertTrue(result is StartConversationResult.Existing)
        result as StartConversationResult.Existing
        assertEquals(created.conversation.id, result.conversation.id)
        assertEquals(2, result.conversation.messages.size)
        assertEquals(4, result.remainingQuota)
        assertEquals(1, repository.snapshot("user-free").conversations.size)
    }

    @Test
    fun blankAndOversizedMessagesAreRejectedWithoutChangingQuota() {
        val repository = InMemoryChatRepository(initialQuota = 5)

        val blank = repository.startConversation("user-free", "maya", "  ")
        val oversized = repository.startConversation("user-free", "maya", "x".repeat(2_001))

        assertTrue(blank is StartConversationResult.InvalidMessage)
        assertTrue(oversized is StartConversationResult.InvalidMessage)
        assertEquals(5, repository.remainingQuota)
    }

    @Test
    fun snapshotDoesNotLeakConversationToUnrelatedUser() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        repository.startConversation("user-free", "maya", "Oi")

        assertTrue(repository.snapshot("unrelated-user").conversations.isEmpty())
    }

    @Test
    fun blockStopsContactAndRemovesConversationForBothUsers() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        repository.startConversation("sam", "user-free", "Oi!")

        val blocked = repository.blockUser("user-free", "sam")
        val retry = repository.startConversation("sam", "user-free", "Outra mensagem")

        assertTrue(blocked)
        assertTrue(retry is StartConversationResult.Blocked)
        assertTrue(repository.snapshot("user-free").conversations.isEmpty())
        assertTrue(repository.snapshot("sam").conversations.isEmpty())
        assertTrue("sam" in repository.snapshot("user-free").blockedUserIds)
    }

    @Test
    fun reportCreatesModerationCaseAndStopsContact() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        val conversation = (repository.startConversation("sam", "user-free", "Oi!") as StartConversationResult.Created)
            .conversation

        val moderationCase = repository.reportUser(
            actorId = "user-free",
            targetUserId = "sam",
            reason = ReportReason.Spam,
            details = "Conteúdo sintético para teste",
        )

        assertNotNull(moderationCase)
        assertEquals(conversation.id, moderationCase?.relatedConversationId)
        assertEquals(ReportReason.Spam, repository.snapshot("user-free").moderationCases.single().reason)
        assertTrue(repository.snapshot("user-free").conversations.isEmpty())
        assertTrue(repository.sendMessage("sam", conversation.id, "Nova mensagem") is SendMessageResult.NotFound)
    }

    @Test
    fun activeMessageDoesNotConsumeNewConversationQuota() {
        val repository = InMemoryChatRepository(initialQuota = 3)
        val conversation = (repository.startConversation("sam", "user-free", "Oi!") as StartConversationResult.Created)
            .conversation
        val quotaAfterOpening = repository.remainingQuota

        val result = repository.sendMessage("user-free", conversation.id, "Que bom falar com você")

        assertTrue(result is SendMessageResult.Sent)
        assertEquals(quotaAfterOpening, repository.remainingQuota)
        assertEquals(2, repository.snapshot("user-free").conversations.single().messages.size)
    }

    @Test
    fun nonParticipantCannotSendMessage() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        val conversation = (repository.startConversation("user-free", "maya", "Oi") as StartConversationResult.Created)
            .conversation

        val result = repository.sendMessage("unrelated-user", conversation.id, "Tentativa")

        assertTrue(result is SendMessageResult.NotAllowed)
    }

    @Test
    fun concurrentOpeningsCannotSpendOneQuotaTwice() {
        val repository = InMemoryChatRepository(initialQuota = 1)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(mutableListOf<StartConversationResult>())

        repeat(2) { index ->
            thread {
                start.await()
                results += repository.startConversation("user-free", "target-$index", "Oi $index")
                done.countDown()
            }
        }
        start.countDown()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, results.count { it is StartConversationResult.Created })
        assertEquals(1, results.count { it is StartConversationResult.QuotaExhausted })
        assertEquals(0, repository.remainingQuota)
    }
}
