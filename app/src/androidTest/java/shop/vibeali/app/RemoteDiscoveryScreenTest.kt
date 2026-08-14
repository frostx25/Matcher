package shop.vibeali.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import shop.vibeali.app.ui.DemoProfile
import shop.vibeali.app.ui.RemoteDiscoveryScreen
import shop.vibeali.app.ui.theme.MatcherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteDiscoveryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactHomeShowsThreeColumnsAndHeaderActions() {
        var openedAccount = 0
        var openedProfile: String? = null
        val profiles = listOf(
            syntheticProfile("user-target-01", "Maya"),
            syntheticProfile("user-target-02", "Noah"),
            syntheticProfile("user-target-03", "Sam"),
        )

        composeRule.setContent {
            MatcherTheme {
                RemoteDiscoveryScreen(
                    profiles = profiles,
                    viewerAvatarUrl = null,
                    viewerInitials = "QA",
                    remainingChats = 5,
                    loading = false,
                    hasMore = false,
                    lookingForGenderIds = setOf("everyone"),
                    onLookingForChange = {},
                    onLoadMore = {},
                    onOpenAccount = { openedAccount += 1 },
                    onOpen = { openedProfile = it },
                )
            }
        }

        composeRule.onNodeWithTag("remote-discovery").assertIsDisplayed()
        composeRule.onNodeWithTag("remote-discovery-header").assertIsDisplayed()
        composeRule.onNodeWithTag("show-nearby-profiles").assertIsDisplayed()
        composeRule.onNodeWithText("Localização aproximada").assertIsDisplayed()
        composeRule.onNodeWithTag("chat-quota").assertIsDisplayed()

        val first = composeRule.onNodeWithTag("remote-profile-user-target-01")
            .fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag("remote-profile-user-target-02")
            .fetchSemanticsNode().boundsInRoot
        val third = composeRule.onNodeWithTag("remote-profile-user-target-03")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.top, third.top, 1f)
        assertTrue(first.left < second.left)
        assertTrue(second.left < third.left)

        composeRule.onNodeWithTag("open-account-from-discovery").performClick()
        composeRule.onNodeWithTag("remote-profile-user-target-02").performClick()
        composeRule.runOnIdle {
            assertEquals(1, openedAccount)
            assertEquals("user-target-02", openedProfile)
        }

        composeRule.onNodeWithTag("toggle-gender-filter").performClick()
        composeRule.onNodeWithTag("save-gender-filter").assertIsDisplayed()
    }

    @Test
    fun favoritesControlShowsOnlySavedProfiles() {
        val nearby = syntheticProfile("user-nearby", "Perto")
        val favorite = syntheticProfile("user-favorite", "Favorita").copy(isFavorite = true)
        composeRule.setContent {
            MatcherTheme {
                RemoteDiscoveryScreen(
                    profiles = listOf(nearby),
                    favoriteProfiles = listOf(favorite),
                    viewerAvatarUrl = null,
                    viewerInitials = "QA",
                    remainingChats = 5,
                    loading = false,
                    hasMore = false,
                    lookingForGenderIds = setOf("everyone"),
                    onLookingForChange = {},
                    onLoadMore = {},
                    onOpenAccount = {},
                    onOpen = {},
                )
            }
        }

        composeRule.onNodeWithTag("show-favorite-profiles").performClick()
        composeRule.onNodeWithTag("remote-profile-user-favorite").assertIsDisplayed()
        composeRule.onNodeWithText("Favoritos (1)").assertIsDisplayed()
    }

    @Test
    fun advancedSearchForwardsPrivateCriteriaAndCanBeCleared() {
        var criteria: List<Any>? = null
        var clearCalls = 0
        composeRule.setContent {
            MatcherTheme {
                RemoteDiscoveryScreen(
                    profiles = emptyList(), viewerAvatarUrl = null, viewerInitials = "QA",
                    remainingChats = 5, loading = false, hasMore = false,
                    lookingForGenderIds = setOf("everyone"), onLookingForChange = {},
                    onLoadMore = {}, onOpenAccount = {}, onOpen = {},
                    advancedDiscoveryActive = true,
                    onAdvancedSearch = { query, min, max, verified, photo ->
                        criteria = listOf(query, min, max, verified, photo)
                    },
                    onClearAdvancedSearch = { clearCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("toggle-gender-filter").performClick()
        composeRule.onNodeWithTag("advanced-search-query").performTextInput("amizade")
        composeRule.onNodeWithTag("apply-advanced-search").performClick()
        composeRule.runOnIdle { assertEquals(listOf("amizade", 18, 99, false, false), criteria) }

        composeRule.onNodeWithTag("toggle-gender-filter").performClick()
        composeRule.onNodeWithTag("clear-advanced-search").performClick()
        composeRule.runOnIdle { assertEquals(1, clearCalls) }
    }

    private fun syntheticProfile(id: String, name: String) = DemoProfile(
        id = id,
        name = name,
        age = 29,
        distance = "na região",
        intent = "Conhecer pessoas",
        bio = "Perfil sintético de teste",
        tags = emptyList(),
        initials = name.take(2).uppercase(),
        colors = listOf(Color(0xFF4D1731), Color(0xFFB93D6C)),
    )
}
