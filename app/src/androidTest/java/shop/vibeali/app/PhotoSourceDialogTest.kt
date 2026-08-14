package shop.vibeali.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import shop.vibeali.app.ui.PhotoInputLauncher
import shop.vibeali.app.ui.PhotoSourceDialog
import shop.vibeali.app.ui.theme.MatcherTheme

class PhotoSourceDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun offersCameraAndGalleryAsSeparateActions() {
        var cameraCalls = 0
        var galleryCalls = 0
        var dismissCalls = 0
        composeRule.setContent {
            MatcherTheme {
                PhotoSourceDialog(
                    visible = true,
                    onDismiss = { dismissCalls += 1 },
                    launcher = PhotoInputLauncher(
                        openGallery = { galleryCalls += 1 },
                        openCamera = { cameraCalls += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("take-photo-now").assertIsDisplayed().performClick()
        assertEquals(1, cameraCalls)
        assertEquals(0, galleryCalls)
        assertEquals(1, dismissCalls)
    }

    @Test
    fun galleryActionIsAvailable() {
        var galleryCalls = 0
        composeRule.setContent {
            MatcherTheme {
                PhotoSourceDialog(
                    visible = true,
                    onDismiss = {},
                    launcher = PhotoInputLauncher(
                        openGallery = { galleryCalls += 1 },
                        openCamera = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("choose-photo-gallery").assertIsDisplayed().performClick()
        assertEquals(1, galleryCalls)
    }
}
