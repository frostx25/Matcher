package com.matcher.app.domain.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeGateTest {
    @Test
    fun birthYearForAdultIsAccepted() {
        assertTrue(AgeGate.isAdult(birthYear = 1995, currentYear = 2026))
    }

    @Test
    fun birthYearForMinorIsRejected() {
        assertFalse(AgeGate.isAdult(birthYear = 2010, currentYear = 2026))
    }

    @Test
    fun futureBirthYearIsRejected() {
        assertFalse(AgeGate.isAdult(birthYear = 2030, currentYear = 2026))
    }
}
