package com.matcher.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GenderControlsTest {
    @Test
    fun exclusiveIdentityReplacesEveryOtherIdentity() {
        assertEquals(
            setOf("prefer_not_to_say"),
            toggleGenderChoice(
                current = setOf("woman", "non_binary"),
                choice = "prefer_not_to_say",
                exclusiveChoice = "prefer_not_to_say",
            ),
        )
    }

    @Test
    fun selectingSpecificPreferenceRemovesEveryone() {
        assertEquals(
            setOf("woman"),
            toggleGenderChoice(
                current = setOf("everyone"),
                choice = "woman",
                exclusiveChoice = "everyone",
            ),
        )
    }

    @Test
    fun multipleSpecificPreferencesRemainSelected() {
        assertEquals(
            setOf("woman", "non_binary"),
            toggleGenderChoice(
                current = setOf("woman"),
                choice = "non_binary",
                exclusiveChoice = "everyone",
            ),
        )
    }
}
