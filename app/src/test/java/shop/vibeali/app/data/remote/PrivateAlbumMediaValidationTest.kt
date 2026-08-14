package shop.vibeali.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAlbumMediaValidationTest {
    @Test
    fun acceptsOnlyBytesMatchingDeclaredPrivateAlbumMediaType() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val webp = "RIFF0000WEBP".encodeToByteArray()

        assertTrue(jpeg.matchesPrivateAlbumMediaType("image/jpeg"))
        assertTrue(png.matchesPrivateAlbumMediaType("image/png"))
        assertTrue(webp.matchesPrivateAlbumMediaType("image/webp"))
        assertFalse(png.matchesPrivateAlbumMediaType("image/jpeg"))
        assertFalse(jpeg.matchesPrivateAlbumMediaType("image/png"))
        assertFalse(webp.matchesPrivateAlbumMediaType("image/gif"))
    }
}
