package com.matcher.app

import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matcher.app.ui.MyPrivateAlbumScreen
import com.matcher.app.ui.ReceivedPrivateAlbumScreen
import com.matcher.app.ui.PrivateAlbumGrantUi
import com.matcher.app.ui.PrivateAlbumPhotoUi
import com.matcher.app.ui.PrivateAlbumImageDecoder
import com.matcher.app.ui.PrivateAlbumTargetUi
import com.matcher.app.ui.PrivateAlbumWarningScreen
import com.matcher.app.ui.SharedPrivateAlbumUi
import com.matcher.app.ui.SharedPrivateAlbumsSection
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

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
                    onRevokeGrants = {},
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
                    onRevokeGrants = {},
                    onDeleteAlbum = {},
                )
            }
        }

        composeRule.onNodeWithText("10/10 fotos · acesso individual").assertIsDisplayed()
        composeRule.onNodeWithTag("add-private-album-photo").assertIsNotEnabled()
        composeRule.onNodeWithText("O álbum chegou ao limite de 10 fotos.").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Foto privada")
            .onFirst()
            .assertIsDisplayed()
    }

    @Test
    fun sharingScreenSelectsMultipleRecipientsBeforeRevoking() {
        val firstId = "00000000-0000-4000-8000-000000000411"
        val secondId = "00000000-0000-4000-8000-000000000412"
        var revoked = emptySet<String>()
        composeRule.setContent {
            MatcherTheme {
                MyPrivateAlbumScreen(
                    albumExists = true,
                    photos = listOf(
                        PrivateAlbumPhotoUi(
                            id = "synthetic-sharing-photo",
                            position = 0,
                            bytes = SYNTHETIC_PIXEL_PNG.copyOf(),
                        ),
                    ),
                    grants = listOf(
                        PrivateAlbumGrantUi(firstId, "Contato A"),
                        PrivateAlbumGrantUi(secondId, "Contato B"),
                    ),
                    targets = listOf(
                        PrivateAlbumTargetUi(firstId, "Contato A", shared = true),
                        PrivateAlbumTargetUi(secondId, "Contato B", shared = true),
                    ),
                    loading = false,
                    errorMessage = null,
                    onBack = {},
                    onAddPhoto = {},
                    onPhotoError = {},
                    onDeletePhoto = {},
                    onToggleGrant = { _, _ -> },
                    onRevokeGrants = { revoked = it },
                    onDeleteAlbum = {},
                )
            }
        }

        composeRule.onNodeWithTag("manage-private-album-sharing").performClick()
        composeRule.onNodeWithTag("private-album-sharing").assertIsDisplayed()
        composeRule.onNodeWithTag("select-album-grant-$firstId").performClick()
        composeRule.onNodeWithTag("select-album-grant-$secondId").performClick()
        composeRule.onNodeWithText("Parar de compartilhar (2)").assertIsDisplayed()
        composeRule.onNodeWithTag("stop-private-album-sharing").performClick()
        composeRule.runOnIdle { assertEquals(setOf(firstId, secondId), revoked) }
    }

    @Test
    fun sharedAlbumListOpensOwnerDirectlyWithoutDiscoveryProfile() {
        val shared = SharedPrivateAlbumUi(
            ownerId = "00000000-0000-4000-8000-000000000401",
            ownerName = "Contato fora da grade",
            itemCount = 2,
        )
        var opened: SharedPrivateAlbumUi? = null
        composeRule.setContent {
            MatcherTheme {
                SharedPrivateAlbumsSection(
                    albums = listOf(shared),
                    onOpen = { opened = it },
                )
            }
        }

        composeRule.onNodeWithTag("shared-private-albums").assertIsDisplayed()
        composeRule.onNodeWithText("Contato fora da grade").assertIsDisplayed()
        composeRule.onNodeWithTag("open-shared-private-album-${shared.ownerId}").performClick()
        composeRule.runOnIdle { assertEquals(shared, opened) }
    }

    @Test
    fun corruptPrivateImageIsRefusedAndRenderedAsUnavailable() {
        val corrupt = PrivateAlbumPhotoUi(
            id = "corrupt-synthetic",
            position = 0,
            bytes = byteArrayOf(0x13, 0x37),
        )
        composeRule.setContent {
            MatcherTheme {
                ReceivedPrivateAlbumScreen(
                    ownerName = "Perfil sintético",
                    photos = listOf(corrupt),
                    loading = false,
                    errorMessage = null,
                    onBack = {},
                    onReport = {},
                )
            }
        }

        composeRule.onNodeWithTag("private-album-invalid-photo-${corrupt.id}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Imagem privada indisponível").assertIsDisplayed()
    }

    @Test
    fun secureDecoderAcceptsSmallImageAndRejectsCorruptPayload() = runBlocking {
        val bitmap = PrivateAlbumImageDecoder.decode(SYNTHETIC_PIXEL_PNG.copyOf())

        assertNotNull(bitmap)
        assertEquals(1, bitmap?.width)
        assertEquals(1, bitmap?.height)
        bitmap?.recycle()
        assertNull(PrivateAlbumImageDecoder.decode(byteArrayOf(0x13, 0x37)))
    }

    private companion object {
        val SYNTHETIC_PIXEL_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )
    }
}
