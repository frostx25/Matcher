package com.matcher.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseErrorMappingTest {
    @Test
    fun mapsKnownServerCodeWithoutExposingDetails() {
        val error = IllegalStateException("request failed: CHAT_QUOTA_EXHAUSTED")

        assertEquals("CHAT_QUOTA_EXHAUSTED", error.matcherCode())
    }

    @Test
    fun unknownFailureDoesNotInventBusinessDecision() {
        val error = IllegalStateException("network unavailable")

        assertNull(error.matcherCode())
    }
}
