package shop.vibeali.app.data.remote

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

    @Test
    fun mapsKnownCodeFromNestedCauseWithoutExposingItsDetails() {
        val error = IllegalStateException(
            "upload failed",
            IllegalStateException("PRIVATE_ALBUM_STORAGE_ACCESS_DENIED: hidden storage details"),
        )

        assertEquals("PRIVATE_ALBUM_STORAGE_ACCESS_DENIED", error.matcherCode())
    }
}
