package com.matcher.app

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matcher.app.ui.RemoteAuthScreen
import com.matcher.app.ui.RemoteOnboardingScreen
import com.matcher.app.ui.OtpDeliveryStatus
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun verifiesSixDigitOtpAutomaticallyInsideTheApp() {
        var verifiedEmail: String? = null
        var verifiedOtp: String? = null
        composeRule.setContent {
            MatcherTheme {
                RemoteAuthScreen(
                    otpRequestedFor = "pessoa@matcher.invalid",
                    otpDeliveryStatus = OtpDeliveryStatus.Confirmed,
                    otpChallengeGeneration = 1,
                    otpResendSecondsRemaining = 0,
                    loading = false,
                    errorMessage = null,
                    onRequestOtp = {},
                    onVerifyOtp = { email, otp ->
                        verifiedEmail = email
                        verifiedOtp = otp
                    },
                    onChangeEmail = {},
                )
            }
        }

        composeRule.onNodeWithTag("remote-otp-input").assertIsDisplayed().performTextInput("12a34567")
        composeRule.runOnIdle {
            assertEquals("pessoa@matcher.invalid", verifiedEmail)
            assertEquals("123456", verifiedOtp)
        }
    }

    @Test
    fun resendStaysDisabledDuringOtpCooldown() {
        composeRule.setContent {
            MatcherTheme {
                RemoteAuthScreen(
                    otpRequestedFor = "pessoa@matcher.invalid",
                    otpDeliveryStatus = OtpDeliveryStatus.Confirmed,
                    otpChallengeGeneration = 1,
                    otpResendSecondsRemaining = 17,
                    loading = false,
                    errorMessage = null,
                    onRequestOtp = {},
                    onVerifyOtp = { _, _ -> },
                    onChangeEmail = {},
                )
            }
        }

        composeRule.onNodeWithTag("resend-email-otp").assertIsNotEnabled()
    }

    @Test
    fun indeterminateDeliveryDoesNotClaimThatTheCodeWasSent() {
        composeRule.setContent {
            MatcherTheme {
                RemoteAuthScreen(
                    otpRequestedFor = "pessoa@matcher.invalid",
                    otpDeliveryStatus = OtpDeliveryStatus.Indeterminate,
                    otpChallengeGeneration = 1,
                    otpResendSecondsRemaining = 60,
                    loading = false,
                    errorMessage = null,
                    onRequestOtp = {},
                    onVerifyOtp = { _, _ -> },
                    onChangeEmail = {},
                )
            }
        }

        composeRule.onNodeWithText("O envio para", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Enviamos um código", substring = true).assertDoesNotExist()
    }

    @Test
    fun newOtpChallengeClearsThePreviousSixDigits() {
        val generation = mutableLongStateOf(1)
        composeRule.setContent {
            MatcherTheme {
                RemoteAuthScreen(
                    otpRequestedFor = "pessoa@matcher.invalid",
                    otpDeliveryStatus = OtpDeliveryStatus.Confirmed,
                    otpChallengeGeneration = generation.longValue,
                    otpResendSecondsRemaining = 0,
                    loading = false,
                    errorMessage = null,
                    onRequestOtp = {},
                    onVerifyOtp = { _, _ -> },
                    onChangeEmail = {},
                )
            }
        }

        composeRule.onNodeWithTag("remote-otp-input").performTextInput("123456")
        composeRule.runOnIdle { generation.longValue += 1 }
        composeRule.onNodeWithTag("remote-otp-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")),
        )
    }

    @Test
    fun onboardingRequiresIdentityAndReturnsTheCompleteSelection() {
        data class Submission(
            val name: String,
            val birthYear: Int,
            val bio: String,
            val intent: String,
            val identityIds: Set<String>,
            val selfDescription: String,
            val identityVisible: Boolean,
            val preferenceIds: Set<String>,
            val acceptedTerms: Boolean,
        )

        var submission: Submission? = null
        composeRule.setContent {
            MatcherTheme {
                RemoteOnboardingScreen(
                    loading = false,
                    errorMessage = null,
                    onComplete = { name, birthYear, bio, intent, identityIds,
                        selfDescription, identityVisible, preferenceIds, acceptedTerms ->
                        submission = Submission(
                            name = name,
                            birthYear = birthYear,
                            bio = bio,
                            intent = intent,
                            identityIds = identityIds,
                            selfDescription = selfDescription,
                            identityVisible = identityVisible,
                            preferenceIds = preferenceIds,
                            acceptedTerms = acceptedTerms,
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag("remote-name-input").performTextInput("Pessoa teste")
        composeRule.onNodeWithTag("remote-birth-year-input").performTextInput("19a95")
        composeRule.onNodeWithTag("remote-onboarding-screen")
            .performScrollToNode(hasTestTag("looking-for-woman"))
        composeRule.onNodeWithTag("looking-for-woman").performClick()
        composeRule.onNodeWithTag("remote-onboarding-screen")
            .performScrollToNode(hasTestTag("remote-terms-confirmation"))
        composeRule.onNodeWithTag("remote-terms-confirmation").performClick()

        composeRule.onNodeWithTag("remote-onboarding-screen")
            .performScrollToNode(hasTestTag("remote-complete-onboarding"))
        composeRule.onNodeWithTag("remote-complete-onboarding")
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(null, submission) }

        composeRule.onNodeWithTag("remote-onboarding-screen")
            .performScrollToNode(hasTestTag("identity-woman"))
        composeRule.onNodeWithTag("identity-woman").performClick()
        composeRule.onNodeWithTag("remote-onboarding-screen")
            .performScrollToNode(hasTestTag("remote-complete-onboarding"))
        composeRule.onNodeWithTag("remote-complete-onboarding")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                Submission(
                    name = "Pessoa teste",
                    birthYear = 1995,
                    bio = "",
                    intent = "Conhecer pessoas",
                    identityIds = setOf("woman"),
                    selfDescription = "",
                    identityVisible = true,
                    preferenceIds = setOf("woman"),
                    acceptedTerms = true,
                ),
                submission,
            )
        }
    }
}
