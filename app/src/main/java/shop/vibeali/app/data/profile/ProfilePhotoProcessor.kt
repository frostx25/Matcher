package shop.vibeali.app.data.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max

internal object ProfilePhotoProcessor {
    private const val MaxDimension = 1_600
    private const val JpegQuality = 85
    private const val MaxEncodedBytes = 5 * 1024 * 1024

    fun prepareJpeg(contentResolver: ContentResolver, uri: Uri): ByteArray {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val largestDimension = max(info.size.width, info.size.height)
                if (largestDimension > MaxDimension) {
                    val scale = MaxDimension.toDouble() / largestDimension
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacyBitmap(contentResolver, uri)
        }

        return try {
            encodeJpeg(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    internal fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JpegQuality, output)) {
            "PROFILE_PHOTO_ENCODING_FAILED"
        }
        return output.toByteArray().also { encoded ->
            require(encoded.size in 1..MaxEncodedBytes) { "PROFILE_PHOTO_TOO_LARGE" }
        }
    }

    private fun decodeLegacyBitmap(contentResolver: ContentResolver, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("PROFILE_PHOTO_UNREADABLE")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "PROFILE_PHOTO_INVALID" }

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MaxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("PROFILE_PHOTO_UNREADABLE")
    }
}
