package com.matcher.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MatcherSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun discoveryGridIsVisible() {
        composeRule.onNodeWithTag("discovery-grid").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-quota").assertIsDisplayed()
    }

    @Test
    fun firstChatRequestConsumesOneFreeOpening() {
        composeRule.onNodeWithTag("profile-maya").performClick()
        composeRule.onNodeWithTag("chat-message-input").performTextInput("Oi, tudo bem?")
        composeRule.onNodeWithTag("send-chat-request").performClick()

        composeRule.onNodeWithText("4 chats").assertIsDisplayed()
    }
}
