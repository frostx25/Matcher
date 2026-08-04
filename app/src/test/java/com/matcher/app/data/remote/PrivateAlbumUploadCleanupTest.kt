package com.matcher.app.data.remote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun lostReservationResponseRetriesOnceWithTheSameIdempotencyKey() = runTest {
        val requestId = "20000000-0000-4000-8000-000000000002"
        val receivedKeys = mutableListOf<String>()

        val result = retryPrivateAlbumReservation(requestId) { idempotencyKey ->
            receivedKeys += idempotencyKey
            if (receivedKeys.size == 1) throw IOException("synthetic lost response")
            "reserved"
        }

        assertEquals("reserved", result)
        assertEquals(listOf(requestId, requestId), receivedKeys)
    }

    @Test
    fun repeatedLostResponsesStopAfterTheSingleRetry() = runTest {
        val requestId = "20000000-0000-4000-8000-000000000002"
        val receivedKeys = mutableListOf<String>()

        val failure = runCatching {
            retryPrivateAlbumReservation<Unit>(requestId) { idempotencyKey ->
                receivedKeys += idempotencyKey
                throw IOException("synthetic repeated lost response")
            }
        }.exceptionOrNull()

        assertEquals(IOException::class, failure?.let { it::class })
        assertEquals(listOf(requestId, requestId), receivedKeys)
    }

    @Test
    fun truncatedReservationResponseRetriesOnceWithTheSameIdempotencyKey() = runTest {
        val requestId = "20000000-0000-4000-8000-000000000002"
        val receivedKeys = mutableListOf<String>()

        val result = retryPrivateAlbumReservation(requestId) { idempotencyKey ->
            receivedKeys += idempotencyKey
            if (receivedKeys.size == 1) throw SerializationException("synthetic truncated response")
            "reserved"
        }

        assertEquals("reserved", result)
        assertEquals(listOf(requestId, requestId), receivedKeys)
    }

    @Test
    fun lostFinalizationResponseRetriesOnceWithTheSameItemId() = runTest {
        val itemId = "30000000-0000-4000-8000-000000000003"
        val receivedItemIds = mutableListOf<String>()

        val result = retryPrivateAlbumFinalization(itemId) { stableItemId ->
            receivedItemIds += stableItemId
            if (receivedItemIds.size == 1) throw IOException("synthetic lost finalize response")
            "available"
        }

        assertEquals("available", result)
        assertEquals(listOf(itemId, itemId), receivedItemIds)
    }

    @Test
    fun businessFailureIsNotRetried() = runTest {
        val requestId = "20000000-0000-4000-8000-000000000002"
        var attempts = 0

        val failure = runCatching {
            retryPrivateAlbumReservation(requestId) {
                attempts += 1
                throw IllegalStateException("PRIVATE_ALBUM_LIMIT_REACHED")
            }
        }.exceptionOrNull()

        assertEquals("PRIVATE_ALBUM_LIMIT_REACHED", failure?.message)
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationIsPropagatedWithoutRetryingReservation() = runTest {
        val requestId = "20000000-0000-4000-8000-000000000002"
        var attempts = 0

        val failure = runCatching {
            retryPrivateAlbumReservation(requestId) {
                attempts += 1
                throw CancellationException("synthetic cancellation")
            }
        }.exceptionOrNull()

        assertEquals(CancellationException::class, failure?.let { it::class })
        assertEquals(1, attempts)
    }

    @Test
    fun availableReservationWithNullObjectPathReturnsCompletedItemWithoutUploading() = runTest {
        val itemId = "30000000-0000-4000-8000-000000000003"
        var uploadCalls = 0
        val reservation = Json.decodeFromString<ReservedPrivateAlbumItem>(
            """
            {
              "item_id": "$itemId",
              "object_path": null,
              "position": 4,
              "reservation_status": "available",
              "upload_expires_at": "2026-08-04T12:00:00Z"
            }
            """.trimIndent(),
        )

        val item = completeOrUploadPrivateAlbumReservation(reservation) {
            uploadCalls += 1
            error("upload must not run for an available reservation")
        }

        assertEquals(PrivateAlbumItem(itemId, 4, "available"), item)
        assertNull(reservation.objectPath)
        assertEquals(0, uploadCalls)
    }
}
