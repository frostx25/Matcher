package com.matcher.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun firstChatRequestConsumesOneFreeOpening() {
        completeOnboarding()
        composeRule.onNodeWithTag("profile-maya").performClick()
        composeRule.onNodeWithTag("chat-message-input").performTextInput("Oi, tudo bem?")
        composeRule.onNodeWithTag("send-chat-request").performClick()

        composeRule.onNodeWithText("4 chats").assertIsDisplayed()
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
