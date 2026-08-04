package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class RemoteProfile(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val age: Int,
    val bio: String,
    val intent: String,
    @SerialName("region_code") val regionCode: String,
    val verified: Boolean,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("gender_identity_ids") val genderIdentityIds: List<String> = listOf("prefer_not_to_say"),
    @SerialName("gender_self_description") val genderSelfDescription: String? = null,
    @SerialName("gender_visible") val genderVisible: Boolean = false,
    @Transient val avatarCandidatePath: String? = null,
    @Transient val avatarModerationStatus: String = "none",
    @Transient val avatarUrl: String? = null,
)

data class DiscoveryPage(
    val profiles: List<RemoteProfile>,
    val nextCursor: String?,
    val preferenceCursorVersion: Long = 0,
)

@Serializable
data class CompleteOnboardingRequest(
    @SerialName("birth_year") val birthYear: Int,
    @SerialName("display_name") val displayName: String,
    @SerialName("region_code") val regionCode: String,
    @SerialName("terms_version") val termsVersion: String,
    @SerialName("terms_accepted") val termsAccepted: Boolean,
    val bio: String,
    val intent: String,
    @SerialName("gender_identity_ids") val genderIdentityIds: List<String> = listOf("prefer_not_to_say"),
    @SerialName("gender_self_description") val genderSelfDescription: String? = null,
    @SerialName("gender_visible") val genderVisible: Boolean = false,
    @SerialName("looking_for_gender_ids") val lookingForGenderIds: List<String> = listOf("everyone"),
)

@Serializable
data class CompleteOnboardingResponse(
    @SerialName("profile_id") val profileId: String,
    @SerialName("account_status") val accountStatus: String,
    @SerialName("calculated_age") val calculatedAge: Int,
)

@Serializable
data class GenderSettings(
    @SerialName("gender_identity_ids") val genderIdentityIds: List<String>,
    @SerialName("gender_self_description") val genderSelfDescription: String? = null,
    @SerialName("gender_visible") val genderVisible: Boolean,
    @SerialName("looking_for_gender_ids") val lookingForGenderIds: List<String>,
    @SerialName("preference_cursor_version") val preferenceCursorVersion: Long,
)

data class UpdateGenderSettingsRequest(
    val genderIdentityIds: List<String>,
    val genderSelfDescription: String? = null,
    val genderVisible: Boolean,
    val lookingForGenderIds: List<String>,
)

interface ProfileGateway {
    suspend fun completeOnboarding(request: CompleteOnboardingRequest): CompleteOnboardingResponse

    suspend fun currentProfile(): RemoteProfile?

    suspend fun discoveryPage(cursor: String? = null, pageSize: Int = 20): DiscoveryPage

    suspend fun discoveryPage(
        cursor: String,
        preferenceCursorVersion: Long,
        pageSize: Int = 20,
    ): DiscoveryPage = discoveryPage(cursor, pageSize)

    suspend fun getGenderSettings(): GenderSettings =
        error("Gender settings are not supported by this gateway")

    suspend fun updateGenderSettings(request: UpdateGenderSettingsRequest): GenderSettings =
        error("Gender settings are not supported by this gateway")

    suspend fun submitProfilePhoto(jpegBytes: ByteArray): RemoteProfile

    suspend fun requestAccountDeletion(): Boolean = false
}

class SupabaseProfileGateway(
    private val client: SupabaseClient,
) : ProfileGateway {
    override suspend fun requestAccountDeletion(): Boolean = client.postgrest
        .rpc("request_account_deletion")
        .decodeAs()

    override suspend fun completeOnboarding(request: CompleteOnboardingRequest): CompleteOnboardingResponse {
        require(request.termsAccepted) { "Terms acceptance is required" }
        val normalizedGenderVisible = request.genderVisible &&
            "prefer_not_to_say" !in request.genderIdentityIds
        validateGenderSettings(
            genderIdentityIds = request.genderIdentityIds,
            genderSelfDescription = request.genderSelfDescription,
            genderVisible = normalizedGenderVisible,
            lookingForGenderIds = request.lookingForGenderIds,
        )
        return client.postgrest
            .rpc(
                "complete_onboarding",
                buildJsonObject {
                    put("birth_year", request.birthYear)
                    put("display_name", request.displayName)
                    put("region_code", request.regionCode)
                    put("terms_version", request.termsVersion)
                    put("terms_accepted", request.termsAccepted)
                    put("bio", request.bio)
                    put("intent", request.intent)
                    put("gender_identity_ids", request.genderIdentityIds.toJsonArray())
                    put("gender_self_description", request.genderSelfDescription?.trim())
                    put("gender_visible", normalizedGenderVisible)
                    put("looking_for_gender_ids", request.lookingForGenderIds.toJsonArray())
                },
            )
            .decodeSingle()
    }

    override suspend fun currentProfile(): RemoteProfile? {
        if (client.auth.currentUserOrNull() == null) return null
        val profile = client.postgrest
            .rpc("get_my_profile")
            .decodeList<RemoteProfile>()
            .singleOrNull()
            ?: return null
        val settings = getGenderSettings()
        val photoState = client.postgrest
            .rpc("get_my_profile_photo_state")
            .decodeList<ProfilePhotoState>()
            .singleOrNull()
        return profile.copy(
            genderIdentityIds = settings.genderIdentityIds,
            genderSelfDescription = settings.genderSelfDescription,
            genderVisible = settings.genderVisible,
        ).withOwnerPhotoState(photoState)
    }

    override suspend fun discoveryPage(cursor: String?, pageSize: Int): DiscoveryPage {
        require(cursor == null) { "PREFERENCE_CURSOR_VERSION_REQUIRED" }
        return loadDiscoveryPage(
            cursor = null,
            preferenceCursorVersion = null,
            pageSize = pageSize,
        )
    }

    override suspend fun discoveryPage(
        cursor: String,
        preferenceCursorVersion: Long,
        pageSize: Int,
    ): DiscoveryPage = loadDiscoveryPage(
        cursor = cursor,
        preferenceCursorVersion = preferenceCursorVersion,
        pageSize = pageSize,
    )

    private suspend fun loadDiscoveryPage(
        cursor: String?,
        preferenceCursorVersion: Long?,
        pageSize: Int,
    ): DiscoveryPage {
        require(pageSize in 1..50)
        requireNotNull(client.auth.currentUserOrNull()) { "Authenticated session required" }
        cursor?.let(::requireUuid)
        require(cursor == null || preferenceCursorVersion != null) {
            "PREFERENCE_CURSOR_VERSION_REQUIRED"
        }
        val rows = client.postgrest.rpc(
            function = "get_discovery_profiles",
            parameters = buildJsonObject {
                put("cursor_profile_id", cursor)
                put("page_size", pageSize)
                put("preference_cursor_version", preferenceCursorVersion)
            },
        ).decodeList<DiscoveryProfileRow>()
        val cursorVersion = rows.firstOrNull()?.preferenceCursorVersion
            ?: getGenderSettings().preferenceCursorVersion
        return DiscoveryPage(
            profiles = rows.map { it.toRemoteProfile().withApprovedAvatar() },
            nextCursor = rows.lastOrNull()?.id.takeIf { rows.lastOrNull()?.hasMore == true },
            preferenceCursorVersion = cursorVersion,
        )
    }

    override suspend fun getGenderSettings(): GenderSettings = client.postgrest
        .rpc("get_my_gender_settings")
        .decodeSingle<GenderSettings>()

    override suspend fun updateGenderSettings(
        request: UpdateGenderSettingsRequest,
    ): GenderSettings {
        val normalizedGenderVisible = request.genderVisible &&
            "prefer_not_to_say" !in request.genderIdentityIds
        validateGenderSettings(
            genderIdentityIds = request.genderIdentityIds,
            genderSelfDescription = request.genderSelfDescription,
            genderVisible = normalizedGenderVisible,
            lookingForGenderIds = request.lookingForGenderIds,
        )
        return client.postgrest.rpc(
            function = "update_gender_settings",
            parameters = buildJsonObject {
                put("gender_identity_ids", request.genderIdentityIds.toJsonArray())
                put("gender_self_description", request.genderSelfDescription?.trim())
                put("gender_visible", normalizedGenderVisible)
                put("looking_for_gender_ids", request.lookingForGenderIds.toJsonArray())
            },
        ).decodeSingle()
    }

    override suspend fun submitProfilePhoto(jpegBytes: ByteArray): RemoteProfile {
        require(jpegBytes.size in 1..MAX_PROFILE_PHOTO_BYTES) {
            "INVALID_PROFILE_PHOTO_SIZE"
        }
        require(jpegBytes.isJpeg()) { "INVALID_PROFILE_PHOTO_FORMAT" }

        val userId = requireNotNull(client.auth.currentUserOrNull()).id
        val objectPath = "$userId/${UUID.randomUUID()}.jpg"
        val bucket = client.storage.from(PROFILE_PHOTOS_BUCKET)
        bucket.upload(objectPath, jpegBytes) {
            upsert = false
            contentType = ContentType.Image.JPEG
        }

        try {
            client.postgrest.rpc(
                "submit_profile_photo",
                buildJsonObject { put("object_path", objectPath) },
            ).decodeSingle<SubmittedProfilePhoto>()
        } catch (error: Exception) {
            runCatching { bucket.delete(objectPath) }
            throw error
        }

        return requireNotNull(currentProfile()) { "PROFILE_NOT_FOUND" }
    }

    private suspend fun RemoteProfile.withOwnerPhotoState(
        state: ProfilePhotoState?,
    ): RemoteProfile {
        val moderationStatus = state?.moderationStatus ?: "none"
        val renderPath = when (moderationStatus) {
            "pending" -> state?.candidatePath
            "blocked_adult", "blocked_abusive" -> null
            else -> state?.approvedPath ?: avatarPath
        }
        return copy(
            avatarCandidatePath = state?.candidatePath,
            avatarModerationStatus = moderationStatus,
            avatarUrl = renderPath?.signedAvatarUrl(),
        )
    }

    private suspend fun RemoteProfile.withApprovedAvatar(): RemoteProfile = copy(
        avatarUrl = avatarPath?.signedAvatarUrl(),
    )

    private suspend fun String.signedAvatarUrl(): String? {
        return try {
            client.storage.from(PROFILE_PHOTOS_BUCKET).createSignedUrl(
                path = this,
                expiresIn = PROFILE_PHOTO_URL_TTL,
            )
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PROFILE_PHOTOS_BUCKET = "profile-photos"
        const val MAX_PROFILE_PHOTO_BYTES = 5 * 1024 * 1024
        val PROFILE_PHOTO_URL_TTL = 15.minutes
    }
}

@Serializable
private data class SubmittedProfilePhoto(
    @SerialName("submitted_path") val submittedPath: String,
    @SerialName("moderation_status") val moderationStatus: String,
)

@Serializable
private data class ProfilePhotoState(
    @SerialName("candidate_path") val candidatePath: String? = null,
    @SerialName("moderation_status") val moderationStatus: String = "none",
    @SerialName("approved_path") val approvedPath: String? = null,
)

@Serializable
private data class DiscoveryProfileRow(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val age: Int,
    val bio: String,
    val intent: String,
    @SerialName("region_code") val regionCode: String,
    val verified: Boolean,
    @SerialName("avatar_path") val avatarPath: String? = null,
    @SerialName("gender_identity_ids") val genderIdentityIds: List<String>,
    @SerialName("gender_self_description") val genderSelfDescription: String? = null,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("preference_cursor_version") val preferenceCursorVersion: Long,
) {
    fun toRemoteProfile() = RemoteProfile(
        id = id,
        displayName = displayName,
        age = age,
        bio = bio,
        intent = intent,
        regionCode = regionCode,
        verified = verified,
        avatarPath = avatarPath,
        genderIdentityIds = genderIdentityIds,
        genderSelfDescription = genderSelfDescription,
        genderVisible = genderIdentityIds.isNotEmpty(),
    )
}

private fun ByteArray.isJpeg(): Boolean =
    size >= 4 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() &&
        this[size - 2] == 0xFF.toByte() && this[size - 1] == 0xD9.toByte()

private fun List<String>.toJsonArray() = buildJsonArray {
    forEach { add(it) }
}

private fun validateGenderSettings(
    genderIdentityIds: List<String>,
    genderSelfDescription: String?,
    genderVisible: Boolean,
    lookingForGenderIds: List<String>,
) {
    require(genderIdentityIds.isNotEmpty() && genderIdentityIds.size <= MAX_GENDER_SELECTIONS) {
        "INVALID_GENDER_IDENTITY"
    }
    require(lookingForGenderIds.isNotEmpty() && lookingForGenderIds.size <= MAX_GENDER_SELECTIONS) {
        "INVALID_DISCOVERY_PREFERENCE"
    }
    require(genderIdentityIds.all { it in GENDER_IDENTITY_IDS } && genderIdentityIds.distinct().size == genderIdentityIds.size) {
        "INVALID_GENDER_IDENTITY"
    }
    require(lookingForGenderIds.all { it in LOOKING_FOR_GENDER_IDS } && lookingForGenderIds.distinct().size == lookingForGenderIds.size) {
        "INVALID_DISCOVERY_PREFERENCE"
    }
    require("prefer_not_to_say" !in genderIdentityIds || genderIdentityIds.size == 1) {
        "INVALID_GENDER_IDENTITY"
    }
    require("everyone" !in lookingForGenderIds || lookingForGenderIds.size == 1) {
        "INVALID_DISCOVERY_PREFERENCE"
    }
    require("prefer_not_to_say" !in genderIdentityIds || !genderVisible) {
        "INVALID_GENDER_VISIBILITY"
    }
    require(genderSelfDescription == null || genderSelfDescription.trim().length in 1..MAX_GENDER_DESCRIPTION_LENGTH) {
        "INVALID_GENDER_SELF_DESCRIPTION"
    }
    require("self_described" !in genderIdentityIds || !genderSelfDescription.isNullOrBlank()) {
        "INVALID_GENDER_SELF_DESCRIPTION"
    }
    require("self_described" in genderIdentityIds || genderSelfDescription == null) {
        "INVALID_GENDER_SELF_DESCRIPTION"
    }
}

private fun requireUuid(value: String) {
    require(runCatching { UUID.fromString(value) }.isSuccess) { "INVALID_DISCOVERY_CURSOR" }
}

private const val MAX_GENDER_SELECTIONS = 12
private const val MAX_GENDER_DESCRIPTION_LENGTH = 60

private val GENDER_IDENTITY_IDS = setOf(
    "woman",
    "man",
    "trans_woman",
    "trans_man",
    "non_binary",
    "genderqueer",
    "self_described",
    "prefer_not_to_say",
)

private val LOOKING_FOR_GENDER_IDS = GENDER_IDENTITY_IDS - "prefer_not_to_say" + "everyone"
