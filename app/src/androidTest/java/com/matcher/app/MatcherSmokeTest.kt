package com.matcher.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matcher.app.domain.profile.LocalProfileStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatcherSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetLocalProfile() {
        composeRule.activityRule.scenario.onActivity {
            it.intent.putExtra(MainActivity.ForceDemoExtra, true)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(LocalProfileStore.PREFS_NAME, 0)
            .edit()
            .clear()
            .commit()
        composeRule.activityRule.scenario.recreate()
    }

    @Test
    fun discoveryGridIsVisible() {
        completeOnboarding()
        composeRule.onNodeWithTag("discovery-grid").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-quota").assertIsDisplayed()
    }

    @Test
    fun firstMessageOpensActiveConversationAndConsumesOneOpening() {
        completeOnboarding()
        composeRule.onNodeWithTag("profile-maya").performClick()
        composeRule.onNodeWithTag("profile-detail-maya").assertIsDisplayed()
        composeRule.onNodeWithTag("start-chat-maya").performScrollTo().performClick()
        composeRule.onNodeWithTag("chat-message-input").performTextInput("Oi, tudo bem?")
        composeRule.onNodeWithTag("send-first-message").performClick()

        composeRule.onNodeWithTag("conversation-detail").assertIsDisplayed()
        composeRule.onNodeWithText("Oi, tudo bem?").assertIsDisplayed()
        composeRule.onNodeWithTag("back-conversation").performClick()
        composeRule.onNodeWithTag("conversations-screen").assertIsDisplayed()
        composeRule.onNodeWithText("4 chats").assertIsDisplayed()
        composeRule.onNodeWithTag("active-maya").assertIsDisplayed()
    }

    @Test
    fun incomingFirstMessageIsActiveWithoutAcceptance() {
        completeOnboarding()
        composeRule.onNodeWithTag("tab-conversations").performClick()
        composeRule.onNodeWithTag("active-sam").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("conversation-detail").assertIsDisplayed()
        composeRule.onNodeWithText("Oi! Curti sua bio. Quer conversar?").assertIsDisplayed()
        composeRule.onNodeWithTag("active-message-input").performTextInput("Que bom falar com você")
        composeRule.onNodeWithTag("send-active-message").performClick()

        composeRule.onNodeWithText("Que bom falar com você").assertIsDisplayed()
    }

    @Test
    fun minorCannotCompleteOnboarding() {
        composeRule.onNodeWithTag("display-name-input").performTextInput("Pessoa teste")
        composeRule.onNodeWithTag("birth-year-input").performTextInput("2010")
        composeRule.onNodeWithTag("adult-confirmation").performClick()
        composeRule.onNodeWithTag("terms-confirmation").performClick()

        composeRule.onNodeWithTag("age-error").assertIsDisplayed()
        composeRule.onNodeWithTag("continue-onboarding").assertIsNotEnabled()
    }

    @Test
    fun profileTabShowsSavedProfile() {
        completeOnboarding()
        composeRule.onNodeWithTag("tab-profile").performClick()

        composeRule.onNodeWithTag("profile-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Pessoa teste, 31").assertIsDisplayed()
    }

    private fun completeOnboarding() {
        composeRule.onNodeWithTag("display-name-input").performTextInput("Pessoa teste")
        composeRule.onNodeWithTag("birth-year-input").performTextInput("1995")
        composeRule.onNodeWithTag("adult-confirmation").performClick()
        composeRule.onNodeWithTag("terms-confirmation").performClick()
        composeRule.onNodeWithTag("continue-onboarding").performClick()
    }
}
