package com.matcher.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Decodes untrusted private media with strict allocation bounds on a worker dispatcher. */
internal object PrivateAlbumImageDecoder {
    suspend fun decode(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
        val bitmap = decodeOnWorker(bytes)
        if (currentCoroutineContext().isActive) {
            bitmap
        } else {
            bitmap?.recycle()
            null
        }
    }

    internal fun decodeOnWorker(bytes: ByteArray): Bitmap? {
        if (bytes.size !in 1..MaxEncodedBytes || !bytes.hasSupportedImageSignature()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: RuntimeException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (!hasSafeSourceBounds(width, height)) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val safe = bitmap.width in 1..MaxDecodedDimension &&
                bitmap.height in 1..MaxDecodedDimension &&
                bitmap.width.toLong() * bitmap.height.toLong() <= MaxDecodedPixels
            if (safe) {
                bitmap
            } else {
                bitmap.recycle()
                null
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    internal fun hasSafeSourceBounds(width: Int, height: Int): Boolean =
        width in 1..MaxSourceDimension &&
            height in 1..MaxSourceDimension &&
            width.toLong() * height.toLong() <= MaxSourcePixels

    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        while (
            width / sampleSize > MaxDecodedDimension ||
            height / sampleSize > MaxDecodedDimension ||
            (width.toLong() * height.toLong()) / sampleSize / sampleSize > MaxDecodedPixels
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun ByteArray.hasSupportedImageSignature(): Boolean {
        val jpeg = size >= 3 &&
            this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
        val png = size >= 8 &&
            this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() &&
            this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
            this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()
        val webp = size >= 12 &&
            this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
        return jpeg || png || webp
    }

    private const val MaxEncodedBytes = 5 * 1024 * 1024
    private const val MaxSourceDimension = 4_096
    private const val MaxSourcePixels = 16_000_000L
    private const val MaxDecodedDimension = 1_280
    private const val MaxDecodedPixels = 1_638_400L
}
