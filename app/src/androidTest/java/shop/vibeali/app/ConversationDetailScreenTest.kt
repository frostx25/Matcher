package shop.vibeali.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import shop.vibeali.app.domain.chat.ChatMessage
import shop.vibeali.app.domain.chat.Conversation
import shop.vibeali.app.domain.chat.ReportReason
import shop.vibeali.app.ui.ConversationDetailScreen
import shop.vibeali.app.ui.DemoProfile
import shop.vibeali.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerKeepsProfileAlbumAndSafetyActionsAccessible() {
        var profileCalls = 0
        var openAlbumCalls = 0
        var toggleAlbumCalls = 0
        var blockCalls = 0
        var reportCalls = 0

        setConversationContent(
            receivedPrivateAlbumAvailable = true,
            myPrivateAlbumAvailable = true,
            onOpenProfile = { profileCalls += 1 },
            onOpenPrivateAlbum = { openAlbumCalls += 1 },
            onTogglePrivateAlbumShare = { toggleAlbumCalls += 1 },
            onBlock = { blockCalls += 1 },
            onReport = { reportCalls += 1 },
        )

        composeRule.onNodeWithTag("open-profile-from-conversation").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("conversation-album-menu").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Abrir álbum recebido").assertIsDisplayed()
        composeRule.onNodeWithText("Liberar meu álbum").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation-open-private-album").performClick()
        composeRule.onNodeWithTag("conversation-album-menu").performClick()
        composeRule.onNodeWithTag("conversation-toggle-private-album").performClick()

        composeRule.onNodeWithTag("conversation-safety-menu").performClick()
        composeRule.onNodeWithText("Bloquear perfil").assertIsDisplayed()
        composeRule.onNodeWithText("Denunciar conversa").assertIsDisplayed()
        composeRule.onNodeWithTag("block-active-conversation").performClick()
        composeRule.onNodeWithText("Bloquear Maya?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-block").performClick()

        composeRule.onNodeWithTag("conversation-safety-menu").performClick()
        composeRule.onNodeWithTag("report-active-conversation").performClick()
        composeRule.onNodeWithText("Denunciar Maya").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-report").performClick()

        composeRule.runOnIdle {
            assertEquals(1, profileCalls)
            assertEquals(1, openAlbumCalls)
            assertEquals(1, toggleAlbumCalls)
            assertEquals(1, blockCalls)
            assertEquals(1, reportCalls)
        }
    }

    @Test
    fun failedSendPreservesDraftAndUnavailableAlbumIsDisabled() {
        var sentBody: String? = null
        setConversationContent(
            receivedPrivateAlbumAvailable = false,
            myPrivateAlbumAvailable = false,
            onSendMessage = {
                sentBody = it
                false
            },
        )

        composeRule.onNodeWithTag("conversation-album-menu").assertIsNotEnabled()
        composeRule.onNodeWithTag("send-active-message").assertIsNotEnabled()
        composeRule.onNodeWithTag("active-message-input").performTextInput("Mensagem sintética")
        composeRule.onNodeWithTag("send-active-message").performClick()
        composeRule.onNodeWithTag("active-message-input").assertTextEquals("Mensagem sintética")
        composeRule.runOnIdle { assertEquals("Mensagem sintética", sentBody) }
    }

    @Test
    fun successfulSendClearsDraft() {
        setConversationContent(onSendMessage = { true })

        composeRule.onNodeWithTag("active-message-input").performTextInput("Tudo certo")
        composeRule.onNodeWithTag("send-active-message").performClick()
        composeRule.onNodeWithTag("send-active-message").assertIsNotEnabled()
    }

    @Test
    fun mediaMenuSeparatesPhotoFromAlbumAndMuteIsAvailable() {
        var toggleAlbumCalls = 0
        var muteValue: Boolean? = null
        setConversationContent(
            myPrivateAlbumAvailable = true,
            onTogglePrivateAlbumShare = { toggleAlbumCalls += 1 },
            onToggleMute = { muteValue = it },
        )

        composeRule.onNodeWithTag("conversation-media-menu").performClick()
        composeRule.onNodeWithText("Selecionar foto").assertIsDisplayed()
        composeRule.onNodeWithText("Liberar meu álbum").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation-media-toggle-album").performClick()
        composeRule.onNodeWithTag("conversation-safety-menu").performClick()
        composeRule.onNodeWithTag("toggle-conversation-mute").performClick()

        composeRule.runOnIdle {
            assertEquals(1, toggleAlbumCalls)
            assertEquals(true, muteValue)
        }
    }

    @Test
    fun messageCanBeRepliedToAndReactedTo() {
        var reply: Pair<String, String>? = null
        var reactionId: String? = null
        setConversationContent(
            onSendReply = { body, messageId -> reply = body to messageId; true },
            onToggleReaction = { reactionId = it },
        )
        composeRule.onNodeWithTag("react-message-message-01").performClick()
        composeRule.onNodeWithTag("reply-message-message-01").performClick()
        composeRule.onNodeWithText("Respondendo").assertIsDisplayed()
        composeRule.onNodeWithTag("active-message-input").performTextInput("Minha resposta")
        composeRule.onNodeWithTag("send-active-message").performClick()
        composeRule.runOnIdle {
            assertEquals("message-01", reactionId)
            assertEquals("Minha resposta" to "message-01", reply)
        }
    }

    @Test
    fun albumShareEventOpensReceivedAlbum() {
        var openCalls = 0
        setConversationContent(
            conversation = syntheticConversation(albumEvent = true),
            onOpenPrivateAlbum = { openCalls += 1 },
        )
        composeRule.onNodeWithTag("album-event-message-01").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, openCalls) }
    }

    @Test
    fun conversationCanBeArchivedFromSafetyMenu() {
        var archived: Boolean? = null
        setConversationContent(onToggleArchive = { archived = it })
        composeRule.onNodeWithTag("conversation-safety-menu").performClick()
        composeRule.onNodeWithTag("toggle-conversation-archive").performClick()
        composeRule.runOnIdle { assertEquals(true, archived) }
    }

    @Test
    fun conversationDeletionRequiresExplicitConfirmation() {
        var deleteCalls = 0
        setConversationContent(onDeleteConversation = { deleteCalls += 1 })
        composeRule.onNodeWithTag("conversation-safety-menu").performClick()
        composeRule.onNodeWithTag("delete-conversation").performClick()
        composeRule.onNodeWithText("Excluir conversa?").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, deleteCalls) }
        composeRule.onNodeWithTag("confirm-delete-conversation").performClick()
        composeRule.runOnIdle { assertEquals(1, deleteCalls) }
    }

    @Test
    fun approximateLocationRequiresConfirmationAndNeverUsesCoordinates() {
        var sentBody: String? = null
        setConversationContent(
            approximateRegion = "br-test",
            onSendMessage = { body -> sentBody = body; true },
        )
        composeRule.onNodeWithTag("conversation-media-menu").performClick()
        composeRule.onNodeWithTag("conversation-share-location").performClick()
        composeRule.onNodeWithText("Compartilhar região aproximada?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-share-location").performClick()
        composeRule.runOnIdle {
            assertEquals("Minha localização aproximada: BR TEST", sentBody)
        }
    }

    @Test
    fun participantTypingStateIsVisibleInHeader() {
        setConversationContent(conversation = syntheticConversation().copy(participantTyping = true))
        composeRule.onNodeWithText("digitando…").assertIsDisplayed()
    }

    private fun setConversationContent(
        receivedPrivateAlbumAvailable: Boolean = false,
        myPrivateAlbumAvailable: Boolean = false,
        onSendMessage: (String) -> Boolean = { true },
        onSendReply: (String, String) -> Boolean = { _, _ -> true },
        onToggleReaction: (String) -> Unit = {},
        onToggleArchive: (Boolean) -> Unit = {},
        conversation: Conversation = syntheticConversation(),
        onOpenProfile: () -> Unit = {},
        onOpenPrivateAlbum: () -> Unit = {},
        onTogglePrivateAlbumShare: () -> Unit = {},
        onToggleMute: (Boolean) -> Unit = {},
        onBlock: () -> Unit = {},
        onReport: () -> Unit = {},
        onDeleteConversation: () -> Unit = {},
        approximateRegion: String? = null,
    ) {
        composeRule.setContent {
            MatcherTheme {
                ConversationDetailScreen(
                    currentUserId = "user-free",
                    conversation = conversation,
                    profile = syntheticProfile(),
                    errorMessage = null,
                    onBack = {},
                    onSendMessage = onSendMessage,
                    onSendReply = onSendReply,
                    onToggleReaction = onToggleReaction,
                    onToggleArchive = onToggleArchive,
                    onBlock = { onBlock() },
                    onReport = { _: String, _: ReportReason, _: String -> onReport() },
                    onDeleteConversation = onDeleteConversation,
                    approximateRegion = approximateRegion,
                    receivedPrivateAlbumAvailable = receivedPrivateAlbumAvailable,
                    myPrivateAlbumAvailable = myPrivateAlbumAvailable,
                    myPrivateAlbumShared = false,
                    onOpenProfile = onOpenProfile,
                    onOpenPrivateAlbum = onOpenPrivateAlbum,
                    onTogglePrivateAlbumShare = onTogglePrivateAlbumShare,
                    onToggleMute = onToggleMute,
                )
            }
        }
    }

    private fun syntheticProfile() = DemoProfile(
        id = "user-target-01",
        name = "Maya",
        age = 29,
        distance = "na região",
        intent = "Conhecer pessoas",
        bio = "Perfil sintético de teste",
        tags = emptyList(),
        initials = "MA",
        colors = listOf(Color(0xFF4D1731), Color(0xFFB93D6C)),
        verified = true,
    )

    private fun syntheticConversation(albumEvent: Boolean = false) = Conversation(
        id = "conversation-01",
        participantIds = setOf("user-free", "user-target-01"),
        startedByUserId = "user-target-01",
        messages = listOf(
            ChatMessage(
                id = "message-01",
                conversationId = "conversation-01",
                senderId = "user-target-01",
                albumEvent = if (albumEvent) "shared" else null,
                albumId = if (albumEvent) "album-01" else null,
                body = "Mensagem direta sintética",
            ),
        ),
    )
}
