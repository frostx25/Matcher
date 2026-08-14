package shop.vibeali.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import shop.vibeali.app.domain.chat.ChatMessage
import shop.vibeali.app.domain.chat.Conversation
import shop.vibeali.app.ui.ConversationsScreen
import shop.vibeali.app.ui.DemoProfile
import shop.vibeali.app.ui.StartConversationDialog
import shop.vibeali.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationEntryScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyListExplainsDirectContactAndReturnsToDiscovery() {
        var exploreCalls = 0
        composeRule.setContent {
            MatcherTheme {
                ConversationsScreen(
                    currentUserId = "user-free",
                    profiles = listOf(syntheticProfile()),
                    conversations = emptyList(),
                    remainingChats = 5,
                    onOpenConversation = {},
                    onExplore = { exploreCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("empty-conversations").assertIsDisplayed()
        composeRule.onNodeWithText("Comece por um perfil").assertIsDisplayed()
        composeRule.onNodeWithText("Mensagens diretas, sem match ou solicitação.").assertIsDisplayed()
        composeRule.onNodeWithTag("explore-from-empty-conversations").performClick()
        composeRule.runOnIdle { assertEquals(1, exploreCalls) }
    }

    @Test
    fun activeListShowsLatestMessageAndOpensConversation() {
        var openedConversation: String? = null
        composeRule.setContent {
            MatcherTheme {
                ConversationsScreen(
                    currentUserId = "user-free",
                    profiles = listOf(syntheticProfile()),
                    conversations = listOf(syntheticConversation()),
                    remainingChats = 5,
                    onOpenConversation = { openedConversation = it },
                    onExplore = {},
                )
            }
        }

        composeRule.onNodeWithTag("empty-conversations").assertDoesNotExist()
        composeRule.onNodeWithText("Mensagem direta sintética").assertIsDisplayed()
        composeRule.onNodeWithTag("active-user-target-01").performClick()
        composeRule.runOnIdle { assertEquals("conversation-01", openedConversation) }
    }

    @Test
    fun firstMessageIdentifiesRecipientAndRequiresText() {
        var sentMessage: String? = null
        composeRule.setContent {
            MatcherTheme {
                StartConversationDialog(
                    profile = syntheticProfile(),
                    remainingChats = 5,
                    errorMessage = null,
                    onDismiss = {},
                    onSend = { sentMessage = it },
                )
            }
        }

        composeRule.onNodeWithTag("start-conversation-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Maya, 29").assertIsDisplayed()
        composeRule.onNodeWithText("5 restantes").assertIsDisplayed()
        composeRule.onNodeWithText("Não há match ou aceite prévio", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("send-first-message").assertIsNotEnabled()
        composeRule.onNodeWithTag("chat-message-input").performTextInput("Primeira mensagem sintética")
        composeRule.onNodeWithTag("send-first-message").performClick()
        composeRule.runOnIdle { assertEquals("Primeira mensagem sintética", sentMessage) }
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

    private fun syntheticConversation() = Conversation(
        id = "conversation-01",
        participantIds = setOf("user-free", "user-target-01"),
        startedByUserId = "user-target-01",
        messages = listOf(
            ChatMessage(
                id = "message-01",
                conversationId = "conversation-01",
                senderId = "user-target-01",
                body = "Mensagem direta sintética",
            ),
        ),
    )
}
