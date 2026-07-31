package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

    suspend fun getPrivateAlbum(ownerId: String): PrivateAlbumContent?

    suspend fun getMyPrivateAlbumGrants(): List<PrivateAlbumGrant>

    suspend fun listPrivateAlbumsSharedWithMe(): List<SharedPrivateAlbum>

    suspend fun uploadPrivateAlbumImage(jpegBytes: ByteArray): PrivateAlbumItem

    /** Returns uncached bytes held only by the caller; no Storage path or signed URL is exposed. */
    suspend fun downloadPrivateAlbumImage(itemId: String): ByteArray

    suspend fun grantPrivateAlbumAccess(recipientId: String): Boolean

    suspend fun revokePrivateAlbumAccess(recipientId: String): Boolean

    suspend fun deletePrivateAlbumImage(itemId: String): Boolean

    suspend fun deletePrivateAlbum(): Boolean

    suspend fun reportPrivateAlbum(
        ownerId: String,
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

    override suspend fun getPrivateAlbum(ownerId: String): PrivateAlbumContent? {
        val normalizedOwnerId = requireUuid(ownerId, "INVALID_ALBUM_OWNER")
        currentUserId()
        val rows = client.postgrest.rpc(
            function = "get_private_album",
            parameters = buildJsonObject { put("album_owner_id", normalizedOwnerId) },
        ).decodeList<PrivateAlbumAccessRow>()
        val albumId = rows.firstOrNull()?.albumId
            ?: listPrivateAlbumsSharedWithMe()
                .firstOrNull { it.ownerId == normalizedOwnerId }
                ?.albumId
            ?: return null
        require(rows.all { it.albumId == albumId }) { "INVALID_PRIVATE_ALBUM_RESPONSE" }
        return PrivateAlbumContent(
            albumId = albumId,
            ownerId = normalizedOwnerId,
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

    override suspend fun uploadPrivateAlbumImage(jpegBytes: ByteArray): PrivateAlbumItem {
        require(jpegBytes.size in 1..MAX_PRIVATE_ALBUM_IMAGE_BYTES) {
            "INVALID_PRIVATE_ALBUM_IMAGE_SIZE"
        }
        require(jpegBytes.isPrivateAlbumJpeg()) { "INVALID_PRIVATE_ALBUM_IMAGE_FORMAT" }

        val ownerId = currentUserId()
        val album = requireNotNull(getMyPrivateAlbum()) { "PRIVATE_ALBUM_NOT_FOUND" }
        val reservation = client.postgrest.rpc(
            function = "reserve_private_album_item",
            parameters = buildJsonObject { put("mime_type", PRIVATE_ALBUM_IMAGE_MIME_TYPE) },
        ).decodeSingle<ReservedPrivateAlbumItem>()

        val bucket = client.storage.from(PRIVATE_ALBUM_BUCKET)
        try {
            validateReservedPath(
                objectPath = reservation.objectPath,
                ownerId = ownerId,
                albumId = album.albumId,
                itemId = reservation.itemId,
            )
            bucket.upload(reservation.objectPath, jpegBytes) {
                upsert = false
                contentType = ContentType.Image.JPEG
            }
            val finalized = client.postgrest.rpc(
                function = "finalize_private_album_item",
                parameters = buildJsonObject { put("album_item_id", reservation.itemId) },
            ).decodeSingle<FinalizedPrivateAlbumItem>()
            require(finalized.itemId == reservation.itemId && finalized.position == reservation.position) {
                "INVALID_PRIVATE_ALBUM_RESPONSE"
            }
            return PrivateAlbumItem(
                itemId = finalized.itemId,
                position = finalized.position,
                itemStatus = finalized.itemStatus,
            )
        } catch (error: Exception) {
            runCatching {
                client.postgrest.rpc(
                    function = "mark_private_album_item_for_deletion",
                    parameters = buildJsonObject { put("album_item_id", reservation.itemId) },
                ).decodeAs<String>()
            }
            runCatching {
                invokePrivateAlbumDelete(
                    buildJsonObject { put("item_id", reservation.itemId) },
                )
            }
            throw error
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
            headers.append(HttpHeaders.Accept, PRIVATE_ALBUM_IMAGE_MIME_TYPE)
            headers.append(HttpHeaders.CacheControl, "no-store")
            headers.append(HttpHeaders.Pragma, "no-cache")
        }
        check(response.status.value in 200..299) { "PRIVATE_ALBUM_NOT_AVAILABLE" }
        check(
            response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.equals(PRIVATE_ALBUM_IMAGE_MIME_TYPE, ignoreCase = true) == true,
        ) { "INVALID_PRIVATE_ALBUM_MEDIA_RESPONSE" }
        check(
            response.headers[HttpHeaders.CacheControl]
                ?.contains("no-store", ignoreCase = true) == true,
        ) { "INVALID_PRIVATE_ALBUM_CACHE_POLICY" }
        val bytes = response.body<ByteArray>()
        check(bytes.size in 1..MAX_PRIVATE_ALBUM_IMAGE_BYTES && bytes.isPrivateAlbumJpeg()) {
            "INVALID_PRIVATE_ALBUM_MEDIA_RESPONSE"
        }
        return bytes
    }

    override suspend fun grantPrivateAlbumAccess(recipientId: String): Boolean =
        client.postgrest.rpc(
            function = "grant_private_album_access",
            parameters = buildJsonObject {
                put("recipient_id", requireUuid(recipientId, "INVALID_ALBUM_RECIPIENT"))
            },
        ).decodeAs()

    override suspend fun revokePrivateAlbumAccess(recipientId: String): Boolean =
        client.postgrest.rpc(
            function = "revoke_private_album_access",
            parameters = buildJsonObject {
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

    override suspend fun deletePrivateAlbum(): Boolean {
        currentUserId()
        return invokePrivateAlbumDelete(
            buildJsonObject { put("delete_album", true) },
        )
    }

    override suspend fun reportPrivateAlbum(
        ownerId: String,
        reason: PrivateAlbumReportReason,
        details: String,
        itemId: String?,
    ): String {
        val normalizedDetails = details.trim()
        require(normalizedDetails.length <= MAX_REPORT_DETAILS_LENGTH) { "INVALID_REPORT_DETAILS" }
        val caseId = client.postgrest.rpc(
            function = "report_private_album",
            parameters = buildJsonObject {
                put("album_owner_id", requireUuid(ownerId, "INVALID_ALBUM_OWNER"))
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
        const val MAX_PRIVATE_ALBUM_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_CONTENT_POLICY_VERSION_LENGTH = 40
        const val MAX_REPORT_DETAILS_LENGTH = 1_000
    }
}

@Serializable
private data class ReservedPrivateAlbumItem(
    @SerialName("item_id") val itemId: String,
    @SerialName("object_path") val objectPath: String,
    val position: Int,
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

private fun ByteArray.isPrivateAlbumJpeg(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() && this[size - 1] == 0xD9.toByte()

private fun requireUuid(value: String, errorCode: String): String =
    runCatching { UUID.fromString(value).toString() }
        .getOrElse { throw IllegalArgumentException(errorCode) }
