package com.matcher.app

import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matcher.app.ui.MyPrivateAlbumScreen
import com.matcher.app.ui.PrivateAlbumPhotoUi
import com.matcher.app.ui.PrivateAlbumWarningScreen
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateAlbumScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun warningKeepsPhotosHiddenUntilExplicitRevealAndShowsCaptureNotice() {
        var revealCalls = 0
        var backCalls = 0
        composeRule.setContent {
            MatcherTheme {
                PrivateAlbumWarningScreen(
                    ownerName = "Perfil sintético",
                    loading = false,
                    errorMessage = null,
                    onBack = { backCalls += 1 },
                    onReveal = { revealCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("private-album-warning").assertIsDisplayed()
        composeRule.onNodeWithText("Conteúdo privado bloqueado").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Capturas ou fotos externas não podem ser impedidas.",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("received-private-album").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Foto privada").assertDoesNotExist()
        composeRule.onNodeWithTag("back-private-album").performClick()
        composeRule.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(0, revealCalls)
        }

        composeRule.onNodeWithTag("reveal-private-album").performClick()
        composeRule.runOnIdle { assertEquals(1, revealCalls) }
    }

    @Test
    fun addingPhotoRequiresAcceptingContentPolicyBeforePickerConfirmation() {
        composeRule.setContent {
            MatcherTheme {
                MyPrivateAlbumScreen(
                    albumExists = false,
                    photos = emptyList(),
                    grants = emptyList(),
                    targets = emptyList(),
                    loading = false,
                    errorMessage = null,
                    onBack = {},
                    onAddPhoto = {},
                    onPhotoError = {},
                    onDeletePhoto = {},
                    onToggleGrant = { _, _ -> },
                    onDeleteAlbum = {},
                )
            }
        }

        composeRule.onNodeWithTag("add-private-album-photo").performClick()
        composeRule.onNodeWithText("Antes de adicionar").assertIsDisplayed()
        composeRule.onNodeWithText("Li e aceito a Política de Conteúdo.").assertIsDisplayed()
        composeRule.onNodeWithTag("accept-private-album-policy").assertIsNotEnabled()

        composeRule.onNode(isToggleable()).performClick()
        composeRule.onNodeWithTag("accept-private-album-policy").assertIsEnabled()
    }

    @Test
    fun albumWithTenSyntheticPhotosShowsAndEnforcesLimit() {
        val photos = List(10) { index ->
            PrivateAlbumPhotoUi(
                id = "synthetic-$index",
                position = index,
                bytes = SYNTHETIC_PIXEL_PNG.copyOf(),
            )
        }
        composeRule.setContent {
            MatcherTheme {
                MyPrivateAlbumScreen(
                    albumExists = true,
                    photos = photos,
                    grants = emptyList(),
                    targets = emptyList(),
                    loading = false,
                    errorMessage = null,
                    onBack = {},
                    onAddPhoto = {},
                    onPhotoError = {},
                    onDeletePhoto = {},
                    onToggleGrant = { _, _ -> },
                    onDeleteAlbum = {},
                )
            }
        }

        composeRule.onNodeWithText("10/10 fotos · acesso individual").assertIsDisplayed()
        composeRule.onNodeWithTag("add-private-album-photo").assertIsNotEnabled()
        composeRule.onNodeWithText("O álbum chegou ao limite de 10 fotos.").assertIsDisplayed()
    }

    private companion object {
        val SYNTHETIC_PIXEL_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )
    }
}
