package com.matcher.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAlbumImageDecoderTest {
    @Test
    fun rejectsInvalidOrBombLikeSourceBoundsBeforePixelAllocation() {
        assertFalse(PrivateAlbumImageDecoder.hasSafeSourceBounds(0, 1080))
        assertFalse(PrivateAlbumImageDecoder.hasSafeSourceBounds(50_000, 50_000))
        assertFalse(PrivateAlbumImageDecoder.hasSafeSourceBounds(16_000, 16_000))
        assertFalse(PrivateAlbumImageDecoder.hasSafeSourceBounds(4_097, 1))
        assertFalse(PrivateAlbumImageDecoder.hasSafeSourceBounds(4_096, 4_096))
        assertTrue(PrivateAlbumImageDecoder.hasSafeSourceBounds(4_000, 4_000))
        assertTrue(PrivateAlbumImageDecoder.hasSafeSourceBounds(4_032, 3_024))
    }

    @Test
    fun calculatesPowerOfTwoSamplingForLargeCameraImage() {
        val sampleSize = PrivateAlbumImageDecoder.sampleSizeFor(4_032, 3_024)

        assertEquals(4, sampleSize)
        assertTrue(4_032 / sampleSize <= 1_280)
        assertTrue((4_032L * 3_024L) / sampleSize / sampleSize <= 1_638_400L)
    }
}
