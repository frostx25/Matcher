package com.matcher.app

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matcher.app.data.remote.AgeVerificationStatus
import com.matcher.app.ui.AgeVerificationScreen
import com.matcher.app.ui.DIDIT_IDENTITY_VERIFICATION_TERMS_URL
import com.matcher.app.ui.DIDIT_VERIFICATION_PRIVACY_NOTICE_URL
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgeVerificationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun optionalVerificationNeedsConsentAndOpensDiditPolicyLinks() {
        var starts = 0
        var backs = 0
        val consentGranted = mutableStateOf(false)
        val openedUris = mutableListOf<String>()
        val testUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides testUriHandler) {
                MatcherTheme {
                    AgeVerificationScreen(
                        status = AgeVerificationStatus.NotStarted,
                        consentGranted = consentGranted.value,
                        loading = false,
                        errorMessage = null,
                        onConsentChanged = { consentGranted.value = it },
                        onStartVerification = { starts += 1 },
                        onRefreshStatus = {},
                        onBack = { backs += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("age-verification-screen").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Você já pode usar o Matcher como não verificado. Esta etapa opcional concede o selo 18+ verificado.",
        ).fetchSemanticsNode()
        composeRule.onNodeWithText(
            "A selfie ao vivo passa por prova de vida passiva e é comparada à foto do documento.",
        ).fetchSemanticsNode()
        composeRule.onNodeWithText(
            "A Didit processa o documento e a selfie para realizar a verificação. " +
                "O Matcher recebe apenas o resultado necessário e não armazena essas imagens.",
        ).fetchSemanticsNode()
        composeRule.onNodeWithTag("start-age-verification").assertIsNotEnabled()
        composeRule.onNodeWithTag("didit-privacy-notice-link").performScrollTo().performClick()
        composeRule.onNodeWithTag("didit-identity-terms-link").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    DIDIT_VERIFICATION_PRIVACY_NOTICE_URL,
                    DIDIT_IDENTITY_VERIFICATION_TERMS_URL,
                ),
                openedUris,
            )
        }

        composeRule.onNodeWithTag("didit-processing-consent").performScrollTo().performClick()
        composeRule.onNodeWithTag("start-age-verification").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Voltar ao perfil").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, backs)
        }
    }

    @Test
    fun manualReviewCannotStartAnotherSessionButCanRefreshStatus() {
        var refreshed = false
        composeRule.setContent {
            MatcherTheme {
                AgeVerificationScreen(
                    status = AgeVerificationStatus.ManualReview,
                    consentGranted = false,
                    loading = false,
                    errorMessage = null,
                    onConsentChanged = {},
                    onStartVerification = {},
                    onRefreshStatus = { refreshed = true },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("start-age-verification").assertIsNotEnabled()
        composeRule.onNodeWithTag("refresh-age-verification")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertTrue(refreshed) }
    }

    @Test
    fun providerFailureIsVisibleAndActionIsDisabledWhileLoading() {
        composeRule.setContent {
            MatcherTheme {
                AgeVerificationScreen(
                    status = AgeVerificationStatus.Pending,
                    consentGranted = true,
                    loading = true,
                    errorMessage = "Falha sintética recuperável",
                    onConsentChanged = {},
                    onStartVerification = {},
                    onRefreshStatus = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("age-verification-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("age-verification-error").assertIsDisplayed()
        composeRule.onNodeWithTag("start-age-verification").assertIsNotEnabled()
        composeRule.onNodeWithTag("refresh-age-verification").assertIsNotEnabled()
    }

    @Test
    fun completeAndCancelledDeepLinksResolveToMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        listOf("complete", "cancelled").forEach { result ->
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("matcher://age-verification/$result"),
            ).addCategory(Intent.CATEGORY_BROWSABLE)
            val matches = context.packageManager.queryIntentActivities(intent, 0)

            assertTrue(
                matches.any {
                    it.activityInfo.packageName == context.packageName &&
                        it.activityInfo.name == MainActivity::class.java.name
                },
            )
        }
    }
}
