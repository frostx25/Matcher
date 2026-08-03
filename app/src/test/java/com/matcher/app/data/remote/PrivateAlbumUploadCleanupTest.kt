package com.matcher.app.data.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateAlbumUploadCleanupTest {
    @Test
    fun cleanupRunsAfterCallerCancellationAndAttemptsBothSteps() = runTest {
        val attempts = mutableListOf<String>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.cancel()
            cleanupFailedPrivateAlbumUpload(
                timeoutMillis = 100,
                markForDeletion = { attempts += "mark" },
                deleteItem = { attempts += "delete" },
            )
        }
        job.join()

        assertEquals(listOf("mark", "delete"), attempts)
    }

    @Test
    fun timedOutMarkDoesNotPreventDeleteAttempt() = runTest {
        val attempts = mutableListOf<String>()

        cleanupFailedPrivateAlbumUpload(
            timeoutMillis = 100,
            markForDeletion = {
                attempts += "mark"
                delay(Long.MAX_VALUE)
            },
            deleteItem = { attempts += "delete" },
        )

        assertEquals(listOf("mark", "delete"), attempts)
    }

    @Test
    fun reservationRequestCarriesStableIdempotencyKey() {
        val albumId = "10000000-0000-4000-8000-000000000001"
        val requestId = "20000000-0000-4000-8000-000000000002"

        val parameters = buildPrivateAlbumReservationParameters(
            albumId = albumId,
            mimeType = "image/jpeg",
            idempotencyKey = requestId,
        )

        assertEquals(3, parameters.size)
        assertEquals(albumId, parameters.getValue("target_album_id").jsonPrimitive.content)
        assertEquals("image/jpeg", parameters.getValue("mime_type").jsonPrimitive.content)
        assertEquals(requestId, parameters.getValue("idempotency_key").jsonPrimitive.content)
    }
}
