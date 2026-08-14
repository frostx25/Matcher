package shop.vibeali.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import shop.vibeali.app.data.profile.ProfilePhotoProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilePhotoProcessorTest {
    @Test
    fun encodedProfilePhotoIsBoundedJpeg() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(50, 45, 54))
        }

        val encoded = try {
            ProfilePhotoProcessor.encodeJpeg(bitmap)
        } finally {
            bitmap.recycle()
        }

        assertTrue(encoded.isNotEmpty())
        assertTrue(encoded.size <= 5 * 1024 * 1024)
        assertEquals(0xFF.toByte(), encoded[0])
        assertEquals(0xD8.toByte(), encoded[1])
        assertEquals(0xFF.toByte(), encoded[encoded.lastIndex - 1])
        assertEquals(0xD9.toByte(), encoded[encoded.lastIndex])
    }
}
