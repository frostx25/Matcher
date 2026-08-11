package com.matcher.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.matcher.app.domain.chat.ChatRepository
import com.matcher.app.domain.chat.ChatSnapshot
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.domain.chat.SendMessageResult
import com.matcher.app.domain.chat.StartConversationResult

data class MatcherUiState(
    val chat: ChatSnapshot,
    val errorMessage: String? = null,
)

class MatcherViewModel(
    private val repository: ChatRepository,
    private val currentUserId: String,
) : ViewModel() {
    var uiState by mutableStateOf(MatcherUiState(chat = repository.snapshot(currentUserId)))
        private set

    fun startConversation(recipientId: String, firstMessage: String): String? {
        val result = repository.startConversation(currentUserId, recipientId, firstMessage)
        val error = when (result) {
            is StartConversationResult.Created,
            is StartConversationResult.Existing -> null
            is StartConversationResult.QuotaExhausted ->
                "Seu limite de novas conversas foi atingido. Conversas existentes continuam liberadas."
            is StartConversationResult.InvalidMessage -> "Escreva uma mensagem válida antes de enviar."
            is StartConversationResult.Blocked -> "Este contato não está disponível."
        }
        refresh(error)
        return when (result) {
            is StartConversationResult.Created -> result.conversation.id
            is StartConversationResult.Existing -> result.conversation.id
            else -> null
        }
    }

    fun sendMessage(conversationId: String, body: String): Boolean {
        val result = repository.sendMessage(currentUserId, conversationId, body)
        val error = when (result) {
            is SendMessageResult.Sent -> null
            is SendMessageResult.InvalidMessage -> "Escreva uma mensagem antes de enviar."
            is SendMessageResult.NotAllowed -> "Esta conversa não permite novas mensagens."
            is SendMessageResult.NotFound -> "A conversa não está mais disponível."
            is SendMessageResult.RateLimited -> "Aguarde um pouco antes de enviar novamente."
        }
        refresh(error)
        return result is SendMessageResult.Sent
    }

    fun sendPhoto(conversationId: String, jpegBytes: ByteArray): Boolean {
        val result = repository.sendPhoto(currentUserId, conversationId, jpegBytes)
        val error = when (result) {
            is SendMessageResult.Sent -> null
            SendMessageResult.InvalidMessage -> "Escolha uma foto válida."
            SendMessageResult.NotAllowed -> "Esta conversa não permite novas mensagens."
            SendMessageResult.NotFound -> "A conversa não está mais disponível."
            SendMessageResult.RateLimited -> "Aguarde um pouco antes de enviar novamente."
        }
        refresh(error)
        return result is SendMessageResult.Sent
    }

    fun blockUser(targetUserId: String): Boolean {
        val blocked = repository.blockUser(currentUserId, targetUserId)
        refresh(if (blocked) null else "Não foi possível bloquear este perfil.")
        return blocked
    }

    fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
    ): Boolean {
        val moderationCase = repository.reportUser(currentUserId, targetUserId, reason, details)
        refresh(if (moderationCase == null) "Não foi possível registrar a denúncia." else null)
        return moderationCase != null
    }

    fun clearError() {
        if (uiState.errorMessage != null) uiState = uiState.copy(errorMessage = null)
    }

    private fun refresh(errorMessage: String?) {
        uiState = MatcherUiState(
            chat = repository.snapshot(currentUserId),
            errorMessage = errorMessage,
        )
    }

    class Factory(
        private val repository: ChatRepository,
        private val currentUserId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MatcherViewModel::class.java))
            return MatcherViewModel(repository, currentUserId) as T
        }
    }
}
