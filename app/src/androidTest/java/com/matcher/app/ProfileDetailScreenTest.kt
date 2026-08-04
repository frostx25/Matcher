package com.matcher.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.ui.DemoProfile
import com.matcher.app.ui.ProfileDetailScreen
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun persistentActionsKeepReceivedAndSharedAlbumOperationsSeparate() {
        var chatCalls = 0
        var openAlbumCalls = 0
        var toggleShareCalls = 0

        setProfileContent(
            receivedPrivateAlbumAvailable = true,
            myPrivateAlbumAvailable = true,
            onStartChat = { chatCalls += 1 },
            onOpenPrivateAlbum = { openAlbumCalls += 1 },
            onTogglePrivateAlbumShare = { toggleShareCalls += 1 },
        )

        composeRule.onNodeWithTag("start-chat-user-target-01").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("profile-album-menu-user-target-01").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Abrir álbum recebido").assertIsDisplayed()
        composeRule.onNodeWithText("Liberar meu álbum").assertIsDisplayed()
        composeRule.onNodeWithTag("open-private-album-user-target-01").performClick()

        composeRule.onNodeWithTag("profile-album-menu-user-target-01").performClick()
        composeRule.onNodeWithTag("toggle-private-album-user-target-01").performClick()
        composeRule.runOnIdle {
            assertEquals(1, chatCalls)
            assertEquals(1, openAlbumCalls)
            assertEquals(1, toggleShareCalls)
        }
    }

    @Test
    fun unavailableAlbumIsDisabledAndSafetyActionsRemainAccessible() {
        var blockCalls = 0
        setProfileContent(
            receivedPrivateAlbumAvailable = false,
            myPrivateAlbumAvailable = false,
            onBlock = { blockCalls += 1 },
        )

        composeRule.onNodeWithTag("profile-album-menu-user-target-01").assertIsNotEnabled()
        composeRule.onNodeWithTag("profile-safety-menu-user-target-01").performClick()
        composeRule.onNodeWithText("Bloquear perfil").assertIsDisplayed()
        composeRule.onNodeWithText("Denunciar perfil").assertIsDisplayed()
        composeRule.onNodeWithTag("block-profile-user-target-01").performClick()
        composeRule.onNodeWithText("Bloquear Maya?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm-block").performClick()

        composeRule.runOnIdle { assertEquals(1, blockCalls) }
    }

    private fun setProfileContent(
        receivedPrivateAlbumAvailable: Boolean,
        myPrivateAlbumAvailable: Boolean,
        onStartChat: () -> Unit = {},
        onOpenPrivateAlbum: () -> Unit = {},
        onTogglePrivateAlbumShare: () -> Unit = {},
        onBlock: () -> Unit = {},
    ) {
        composeRule.setContent {
            MatcherTheme {
                ProfileDetailScreen(
                    profile = syntheticProfile(),
                    remainingChats = 5,
                    onBack = {},
                    onStartChat = onStartChat,
                    onBlock = onBlock,
                    onReport = { _: ReportReason, _: String -> },
                    receivedPrivateAlbumAvailable = receivedPrivateAlbumAvailable,
                    myPrivateAlbumAvailable = myPrivateAlbumAvailable,
                    myPrivateAlbumShared = false,
                    onOpenPrivateAlbum = onOpenPrivateAlbum,
                    onTogglePrivateAlbumShare = onTogglePrivateAlbumShare,
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
        bio = "Café sem pressa e conversas que fogem do óbvio.",
        tags = listOf("Café", "Shows"),
        initials = "MA",
        colors = listOf(Color(0xFF4D1731), Color(0xFFB93D6C)),
        verified = true,
    )
}
