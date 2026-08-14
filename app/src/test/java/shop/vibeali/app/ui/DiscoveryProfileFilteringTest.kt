package shop.vibeali.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryProfileFilteringTest {
    @Test
    fun currentUserIsNeverRenderedInDiscovery() {
        val current = testProfile("current")
        val other = testProfile("other")

        assertEquals(listOf(other), listOf(current, other).excludingCurrentUser(current.id))
    }

    private fun testProfile(id: String) = DemoProfile(
        id = id,
        name = id,
        age = 30,
        distance = "na região",
        bio = "",
        intent = "",
        tags = emptyList(),
        initials = id.take(2).uppercase(),
        colors = listOf(Color.Black, Color.DarkGray),
    )
}
