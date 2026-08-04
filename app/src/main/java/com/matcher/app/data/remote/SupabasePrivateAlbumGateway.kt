package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PrivateAlbum(
    @SerialName("album_id") val albumId: String,
    @SerialName("album_status") val albumStatus: String,
    @SerialName("item_count") val itemCount: Int,
)

@Serializable
data class PrivateAlbumItem(
    @SerialName("item_id") val itemId: String,
    val position: Int,
    @SerialName("item_status") val itemStatus: String = "available",
)

data class PrivateAlbumContent(
    val albumId: String,
    val ownerId: String,
    val items: List<PrivateAlbumItem>,
)

@Serializable
data class PrivateAlbumGrant(
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("granted_at") val grantedAt: String,
)

@Serializable
data class SharedPrivateAlbum(
    @SerialName("album_id") val albumId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("owner_display_name") val ownerDisplayName: String,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("granted_at") val grantedAt: String,
)

enum class PrivateAlbumReportReason(val remoteValue: String) {
    InappropriatePhoto("inappropriate_photo"),
    Spam("spam"),
    Harassment("harassment"),
    FakeProfile("fake_profile"),
    Other("other"),
}

interface PrivateAlbumGateway {
    suspend fun createPrivateAlbum(
        contentPolicyVersion: String,
        contentPolicyAccepted: Boolean,
    ): PrivateAlbum

    suspend fun getMyPrivateAlbum(): PrivateAlbum?

    suspend fun getMyPrivateAlbumItems(): List<PrivateAlbumItem>

    suspend fun getPrivateAlbum(albumId: String): PrivateAlbumContent?

    suspend fun getMyPrivateAlbumGrants(): List<PrivateAlbumGrant>

    suspend fun listPrivateAlbumsSharedWithMe(): List<SharedPrivateAlbum>

    suspend fun uploadPrivateAlbumImage(albumId: String, jpegBytes: ByteArray): PrivateAlbumItem

    /** Returns uncached bytes held only by the caller; no Storage path or signed URL is exposed. */
    suspend fun downloadPrivateAlbumImage(itemId: String): ByteArray

    suspend fun grantPrivateAlbumAccess(albumId: String, recipientId: String): Boolean

    suspend fun revokePrivateAlbumAccess(albumId: String, recipientId: String): Boolean

    suspend fun deletePrivateAlbumImage(itemId: String): Boolean

    suspend fun deletePrivateAlbum(albumId: String): Boolean

    suspend fun reportPrivateAlbum(
        albumId: String,
        reason: PrivateAlbumReportReason,
        details: String = "",
        itemId: String? = null,
    ): String
}

class SupabasePrivateAlbumGateway(
    private val client: SupabaseClient,
) : PrivateAlbumGateway {
    override suspend fun createPrivateAlbum(
        contentPolicyVersion: String,
        contentPolicyAccepted: Boolean,
    ): PrivateAlbum {
        require(contentPolicyAccepted) { "CONTENT_POLICY_REQUIRED" }
        val normalizedVersion = contentPolicyVersion.trim()
        require(normalizedVersion.length in 1..MAX_CONTENT_POLICY_VERSION_LENGTH) {
            "INVALID_CONTENT_POLICY_VERSION"
        }
        val albumId = client.postgrest.rpc(
            function = "create_private_album",
            parameters = buildJsonObject {
                put("content_policy_version", normalizedVersion)
                put("content_policy_accepted", true)
            },
        ).decodeAs<String>()
        requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE")
        return requireNotNull(getMyPrivateAlbum()) { "PRIVATE_ALBUM_NOT_FOUND" }.also {
            require(it.albumId == albumId) { "INVALID_PRIVATE_ALBUM_RESPONSE" }
        }
    }

    override suspend fun getMyPrivateAlbum(): PrivateAlbum? {
        currentUserId()
        return client.postgrest.rpc("get_my_private_album")
            .decodeList<PrivateAlbum>()
            .singleOrNull()
    }

    override suspend fun getMyPrivateAlbumItems(): List<PrivateAlbumItem> {
        currentUserId()
        return client.postgrest.rpc("list_my_private_album_items")
            .decodeList()
    }

    override suspend fun getPrivateAlbum(albumId: String): PrivateAlbumContent? {
        val normalizedAlbumId = requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE")
        currentUserId()
        val sharedAlbum = listPrivateAlbumsSharedWithMe()
            .firstOrNull { it.albumId == normalizedAlbumId }
            ?: return null
        val rows = client.postgrest.rpc(
            function = "get_private_album",
            parameters = buildJsonObject { put("target_album_id", normalizedAlbumId) },
        ).decodeList<PrivateAlbumAccessRow>()
        require(rows.all { it.albumId == normalizedAlbumId }) { "INVALID_PRIVATE_ALBUM_RESPONSE" }
        return PrivateAlbumContent(
            albumId = normalizedAlbumId,
            ownerId = sharedAlbum.ownerId,
            items = rows.map { row ->
                PrivateAlbumItem(
                    itemId = row.itemId,
                    position = row.position,
                )
            },
        )
    }

    override suspend fun getMyPrivateAlbumGrants(): List<PrivateAlbumGrant> {
        currentUserId()
        return client.postgrest.rpc("list_private_album_grants")
            .decodeList()
    }

    override suspend fun listPrivateAlbumsSharedWithMe(): List<SharedPrivateAlbum> {
        currentUserId()
        return client.postgrest.rpc("list_private_albums_shared_with_me")
            .decodeList()
    }

    override suspend fun uploadPrivateAlbumImage(
        albumId: String,
        jpegBytes: ByteArray,
    ): PrivateAlbumItem {
        require(jpegBytes.size in 1..MAX_PRIVATE_ALBUM_IMAGE_BYTES) {
            "INVALID_PRIVATE_ALBUM_IMAGE_SIZE"
        }
        require(jpegBytes.isPrivateAlbumJpeg()) { "INVALID_PRIVATE_ALBUM_IMAGE_FORMAT" }

        val ownerId = currentUserId()
        val normalizedAlbumId = requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE")
        val reservationKey = UUID.randomUUID().toString()
        val reservation = retryPrivateAlbumReservation(reservationKey) { stableReservationKey ->
            client.postgrest.rpc(
                function = "reserve_private_album_item",
                parameters = buildPrivateAlbumReservationParameters(
                    albumId = normalizedAlbumId,
                    mimeType = PRIVATE_ALBUM_IMAGE_MIME_TYPE,
                    idempotencyKey = stableReservationKey,
                ),
            ).decodeSingle<ReservedPrivateAlbumItem>()
        }
        return completeOrUploadPrivateAlbumReservation(reservation) { activeReservation ->
            val reservationItemId = requireUuid(
                activeReservation.itemId,
                "INVALID_PRIVATE_ALBUM_RESPONSE",
            )
            val reservationPosition = requireNotNull(activeReservation.position) {
                "INVALID_PRIVATE_ALBUM_RESPONSE"
            }
            val bucket = client.storage.from(PRIVATE_ALBUM_BUCKET)
            try {
                require(
                    activeReservation.reservationStatus == "uploading" &&
                        !activeReservation.uploadExpiresAt.isNullOrBlank(),
                ) { "INVALID_PRIVATE_ALBUM_RESPONSE" }
                val objectPath = requireNotNull(activeReservation.objectPath) {
                    "INVALID_PRIVATE_ALBUM_RESPONSE"
                }
                validateReservedPath(
                    objectPath = objectPath,
                    ownerId = ownerId,
                    albumId = normalizedAlbumId,
                    itemId = reservationItemId,
                )
                try {
                    bucket.upload(objectPath, jpegBytes) {
                        upsert = false
                        contentType = ContentType.Image.JPEG
                    }
                } catch (error: Exception) {
                    if (error.isPrivateAlbumStorageAccessDenied()) {
                        throw PrivateAlbumStorageAccessException(error)
                    }
                    throw error
                }
                val finalized = retryPrivateAlbumFinalization(reservationItemId) { stableItemId ->
                    client.postgrest.rpc(
                        function = "finalize_private_album_item",
                        parameters = buildJsonObject { put("album_item_id", stableItemId) },
                    ).decodeSingle<FinalizedPrivateAlbumItem>()
                }
                require(
                    finalized.itemId == reservationItemId &&
                        finalized.position == reservationPosition &&
                        finalized.itemStatus == "available",
                ) {
                    "INVALID_PRIVATE_ALBUM_RESPONSE"
                }
                PrivateAlbumItem(
                    itemId = finalized.itemId,
                    position = finalized.position,
                    itemStatus = finalized.itemStatus,
                )
            } catch (error: Exception) {
                cleanupFailedPrivateAlbumUpload(
                    markForDeletion = {
                        client.postgrest.rpc(
                            function = "mark_private_album_item_for_deletion",
                            parameters = buildJsonObject { put("album_item_id", reservationItemId) },
                        )
                    },
                    deleteItem = {
                        invokePrivateAlbumDelete(
                            buildJsonObject { put("item_id", reservationItemId) },
                        )
                    },
                )
                throw error
            }
        }
    }

    override suspend fun downloadPrivateAlbumImage(itemId: String): ByteArray {
        val normalizedItemId = requireUuid(itemId, "INVALID_PRIVATE_ALBUM_ITEM")
        currentUserId()
        val response = client.functions.invoke(
            function = PRIVATE_ALBUM_MEDIA_FUNCTION,
        ) {
            method = HttpMethod.Get
            url.parameters.append("item_id", normalizedItemId)
            headers.append(HttpHeaders.Accept, PRIVATE_ALBUM_ACCEPT_TYPES)
            headers.append(HttpHeaders.CacheControl, "no-store")
            headers.append(HttpHeaders.Pragma, "no-cache")
        }
        check(response.status.value in 200..299) { "PRIVATE_ALBUM_NOT_AVAILABLE" }
        val mediaType = response.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        check(mediaType in PRIVATE_ALBUM_DOWNLOAD_MIME_TYPES) {
            "INVALID_PRIVATE_ALBUM_MEDIA_RESPONSE"
        }
        check(
            response.headers[HttpHeaders.CacheControl]
                ?.contains("no-store", ignoreCase = true) == true,
        ) { "INVALID_PRIVATE_ALBUM_CACHE_POLICY" }
        val bytes = response.body<ByteArray>()
        check(
            bytes.size in 1..MAX_PRIVATE_ALBUM_IMAGE_BYTES &&
                mediaType != null && bytes.matchesPrivateAlbumMediaType(mediaType),
        ) {
            "INVALID_PRIVATE_ALBUM_MEDIA_RESPONSE"
        }
        return bytes
    }

    override suspend fun grantPrivateAlbumAccess(albumId: String, recipientId: String): Boolean =
        client.postgrest.rpc(
            function = "grant_private_album_access",
            parameters = buildJsonObject {
                put("target_album_id", requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE"))
                put("recipient_id", requireUuid(recipientId, "INVALID_ALBUM_RECIPIENT"))
            },
        ).decodeAs()

    override suspend fun revokePrivateAlbumAccess(albumId: String, recipientId: String): Boolean =
        client.postgrest.rpc(
            function = "revoke_private_album_access",
            parameters = buildJsonObject {
                put("target_album_id", requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE"))
                put("recipient_id", requireUuid(recipientId, "INVALID_ALBUM_RECIPIENT"))
            },
        ).decodeAs()

    override suspend fun deletePrivateAlbumImage(itemId: String): Boolean {
        val normalizedItemId = requireUuid(itemId, "INVALID_PRIVATE_ALBUM_ITEM")
        currentUserId()
        return invokePrivateAlbumDelete(
            buildJsonObject { put("item_id", normalizedItemId) },
        )
    }

    override suspend fun deletePrivateAlbum(albumId: String): Boolean {
        currentUserId()
        return invokePrivateAlbumDelete(
            buildJsonObject {
                put("album_id", requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE"))
            },
        )
    }

    override suspend fun reportPrivateAlbum(
        albumId: String,
        reason: PrivateAlbumReportReason,
        details: String,
        itemId: String?,
    ): String {
        val normalizedDetails = details.trim()
        require(normalizedDetails.length <= MAX_REPORT_DETAILS_LENGTH) { "INVALID_REPORT_DETAILS" }
        val caseId = client.postgrest.rpc(
            function = "report_private_album",
            parameters = buildJsonObject {
                put("target_album_id", requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE"))
                put("report_reason", reason.remoteValue)
                put("report_details", normalizedDetails)
                put("album_item_id", itemId?.let { requireUuid(it, "INVALID_PRIVATE_ALBUM_ITEM") })
            },
        ).decodeAs<String>()
        return requireUuid(caseId, "INVALID_MODERATION_CASE_RESPONSE")
    }

    private fun currentUserId(): String = requireNotNull(client.auth.currentUserOrNull()) {
        "Authenticated session required"
    }.id

    private fun validateReservedPath(
        objectPath: String,
        ownerId: String,
        albumId: String,
        itemId: String,
    ) {
        requireUuid(albumId, "INVALID_PRIVATE_ALBUM_RESPONSE")
        requireUuid(itemId, "INVALID_PRIVATE_ALBUM_RESPONSE")
        require(objectPath == "$ownerId/$albumId/$itemId.jpg") {
            "INVALID_PRIVATE_ALBUM_PATH"
        }
    }

    private suspend fun invokePrivateAlbumDelete(body: JsonObject): Boolean {
        val response = client.functions.invoke(
            function = PRIVATE_ALBUM_DELETE_FUNCTION,
            body = body,
            headers = Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                append(HttpHeaders.CacheControl, "no-store")
                append(HttpHeaders.Pragma, "no-cache")
            },
        )
        check(response.status.value in 200..299) { "PRIVATE_ALBUM_DELETE_FAILED" }
        check(
            response.headers[HttpHeaders.CacheControl]
                ?.contains("no-store", ignoreCase = true) == true,
        ) { "INVALID_PRIVATE_ALBUM_CACHE_POLICY" }
        val result = response.body<PrivateAlbumDeleteResponse>()
        check(result.deleted) { "PRIVATE_ALBUM_DELETE_FAILED" }
        return true
    }

    private companion object {
        const val PRIVATE_ALBUM_BUCKET = "private-albums"
        const val PRIVATE_ALBUM_MEDIA_FUNCTION = "private-album-media"
        const val PRIVATE_ALBUM_DELETE_FUNCTION = "private-album-delete"
        const val PRIVATE_ALBUM_IMAGE_MIME_TYPE = "image/jpeg"
        const val PRIVATE_ALBUM_ACCEPT_TYPES = "image/jpeg, image/png, image/webp"
        val PRIVATE_ALBUM_DOWNLOAD_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_PRIVATE_ALBUM_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_CONTENT_POLICY_VERSION_LENGTH = 40
        const val MAX_REPORT_DETAILS_LENGTH = 1_000
    }
}

@Serializable
internal data class ReservedPrivateAlbumItem(
    @SerialName("item_id") val itemId: String,
    @SerialName("object_path") val objectPath: String? = null,
    val position: Int? = null,
    @SerialName("reservation_status") val reservationStatus: String,
    @SerialName("upload_expires_at") val uploadExpiresAt: String? = null,
)

@Serializable
private data class FinalizedPrivateAlbumItem(
    @SerialName("item_id") val itemId: String,
    val position: Int,
    @SerialName("item_status") val itemStatus: String,
)

@Serializable
private data class PrivateAlbumAccessRow(
    @SerialName("album_id") val albumId: String,
    @SerialName("item_id") val itemId: String,
    val position: Int,
)

@Serializable
private data class PrivateAlbumDeleteResponse(
    val deleted: Boolean,
)

internal class PrivateAlbumStorageAccessException(
    cause: Throwable,
) : Exception("PRIVATE_ALBUM_STORAGE_ACCESS_DENIED", cause)

internal suspend fun cleanupFailedPrivateAlbumUpload(
    timeoutMillis: Long = 5_000,
    markForDeletion: suspend () -> Unit,
    deleteItem: suspend () -> Unit,
) {
    require(timeoutMillis > 0)
    withContext(NonCancellable) {
        runPrivateAlbumCleanupStep(timeoutMillis, markForDeletion)
        runPrivateAlbumCleanupStep(timeoutMillis, deleteItem)
    }
}

internal fun buildPrivateAlbumReservationParameters(
    albumId: String,
    mimeType: String,
    idempotencyKey: String,
): JsonObject = buildJsonObject {
    put("target_album_id", albumId)
    put("mime_type", mimeType)
    put("idempotency_key", idempotencyKey)
}

internal suspend fun <T> retryPrivateAlbumReservation(
    idempotencyKey: String,
    maxAttempts: Int = 2,
    reserve: suspend (idempotencyKey: String) -> T,
): T = retryPrivateAlbumIdempotentRequest(
    stableId = idempotencyKey,
    invalidIdError = "INVALID_PRIVATE_ALBUM_RESERVATION_KEY",
    maxAttempts = maxAttempts,
    request = reserve,
)

internal suspend fun <T> retryPrivateAlbumFinalization(
    itemId: String,
    maxAttempts: Int = 2,
    finalize: suspend (itemId: String) -> T,
): T = retryPrivateAlbumIdempotentRequest(
    stableId = itemId,
    invalidIdError = "INVALID_PRIVATE_ALBUM_ITEM",
    maxAttempts = maxAttempts,
    request = finalize,
)

private suspend fun <T> retryPrivateAlbumIdempotentRequest(
    stableId: String,
    invalidIdError: String,
    maxAttempts: Int,
    request: suspend (stableId: String) -> T,
): T {
    require(maxAttempts > 0)
    requireUuid(stableId, invalidIdError)
    var attempt = 1
    while (true) {
        try {
            return request(stableId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (attempt >= maxAttempts || !error.isPrivateAlbumReservationResponseUncertain()) {
                throw error
            }
            attempt += 1
        }
    }
}

internal fun ReservedPrivateAlbumItem.toAlreadyAvailableItemOrNull(): PrivateAlbumItem? {
    if (reservationStatus != "available") return null
    return PrivateAlbumItem(
        itemId = requireUuid(itemId, "INVALID_PRIVATE_ALBUM_RESPONSE"),
        position = requireNotNull(position) { "INVALID_PRIVATE_ALBUM_RESPONSE" },
        itemStatus = "available",
    )
}

internal suspend fun completeOrUploadPrivateAlbumReservation(
    reservation: ReservedPrivateAlbumItem,
    uploadReservedItem: suspend (ReservedPrivateAlbumItem) -> PrivateAlbumItem,
): PrivateAlbumItem = reservation.toAlreadyAvailableItemOrNull()
    ?: uploadReservedItem(reservation)

private suspend fun runPrivateAlbumCleanupStep(
    timeoutMillis: Long,
    action: suspend () -> Unit,
) {
    try {
        withTimeout(timeoutMillis) { action() }
    } catch (_: TimeoutCancellationException) {
    } catch (_: Exception) {
    }
}

internal fun Throwable.isPrivateAlbumStorageAccessDenied(): Boolean = selfAndCauses().any { error ->
    val statusCode = when (error) {
        is RestException -> error.statusCode
        is ResponseException -> error.response.status.value
        else -> null
    }
    val normalizedMessage = error.message.orEmpty().lowercase()
    statusCode == 401 || statusCode == 403 ||
        normalizedMessage.contains("row-level security") ||
        normalizedMessage.contains("row level security") ||
        normalizedMessage.contains("42501") ||
        normalizedMessage.contains("unauthorized") ||
        normalizedMessage.contains("forbidden")
}

internal fun Throwable.isPrivateAlbumReservationResponseUncertain(): Boolean = selfAndCauses().any { error ->
    when (error) {
        is HttpRequestTimeoutException,
        is HttpRequestException,
        is SocketTimeoutException,
        is IOException,
        is SerializationException,
        -> true
        is RestException -> error.statusCode == 408 || error.statusCode >= 500
        is ResponseException -> {
            val statusCode = error.response.status.value
            statusCode == 408 || statusCode >= 500
        }
        else -> false
    }
}

private fun ByteArray.isPrivateAlbumJpeg(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() && this[size - 1] == 0xD9.toByte()

internal fun ByteArray.matchesPrivateAlbumMediaType(mediaType: String): Boolean = when (mediaType.lowercase()) {
    "image/jpeg" -> isPrivateAlbumJpeg()
    "image/png" -> size >= 8 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() &&
        this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
        this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()
    "image/webp" -> size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    else -> false
}

private fun requireUuid(value: String, errorCode: String): String =
    runCatching { UUID.fromString(value).toString() }
        .getOrElse { throw IllegalArgumentException(errorCode) }
