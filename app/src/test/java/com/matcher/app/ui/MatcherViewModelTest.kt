package com.matcher.app.ui

import com.matcher.app.domain.chat.InMemoryChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MatcherViewModelTest {
    @Test
    fun firstMessageCreatesConversationAndRefreshesQuota() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        val viewModel = MatcherViewModel(repository, DemoUserId)

        val conversationId = viewModel.startConversation("maya", "Oi, tudo bem?")

        assertNotNull(conversationId)
        assertEquals(4, viewModel.uiState.chat.remainingQuota)
        assertEquals(conversationId, viewModel.uiState.chat.conversations.single().id)
        assertEquals("Oi, tudo bem?", viewModel.uiState.chat.conversations.single().messages.single().body)
    }

    @Test
    fun existingConversationReceivesMessageWithoutNewOpening() {
        val repository = InMemoryChatRepository(initialQuota = 5)
        val viewModel = MatcherViewModel(repository, DemoUserId)
        val conversationId = viewModel.startConversation("maya", "Oi!")

        val existingId = viewModel.startConversation("maya", "Outra mensagem")

        assertEquals(conversationId, existingId)
        assertEquals(4, viewModel.uiState.chat.remainingQuota)
        assertEquals(2, viewModel.uiState.chat.conversations.single().messages.size)
    }
}
