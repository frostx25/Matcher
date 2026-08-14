package com.matcher.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.matcher.app.ui.SubscriptionPlansScreen
import com.matcher.app.ui.theme.MatcherTheme
import org.junit.Rule
import org.junit.Test

class SubscriptionPlansScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun showsFourPlansAndDoesNotOfferARealPurchase() {
        composeRule.setContent { MatcherTheme { SubscriptionPlansScreen(onBack = {}) } }
        composeRule.onNodeWithTag("subscription-plans-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("plan-free").assertIsDisplayed()
        composeRule.onNodeWithTag("plan-extra").assertIsDisplayed()
        composeRule.onNodeWithText("Assinaturas em breve. Os valores são referências; nenhuma cobrança será feita agora.").assertIsDisplayed()
    }

    @Test fun bottomNavigationExposesPlansBesideProfile() {
        composeRule.setContent {
            MatcherTheme { com.matcher.app.ui.BottomNavigationBar(selectedTab = 3, onTabSelected = {}) }
        }
        composeRule.onNodeWithTag("tab-plans").assertIsDisplayed()
        composeRule.onNodeWithTag("tab-profile").assertIsDisplayed()
    }
}
