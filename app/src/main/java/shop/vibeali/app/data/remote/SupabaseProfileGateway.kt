package shop.vibeali.app.data.remote

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
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("activity_status") val activityStatus: String? = null,
    val interests: List<String> = emptyList(),
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
data class PrivacySettings(
    @SerialName("show_activity_status") val showActivityStatus: Boolean = true,
)

data class PrivacyCenter(
    val settings: PrivacySettings = PrivacySettings(),
    val hiddenProfiles: List<RemoteProfile> = emptyList(),
    val blockedProfiles: List<RemoteProfile> = emptyList(),
)

@Serializable
data class ActiveSanction(
    @SerialName("sanction_id") val sanctionId: String,
    @SerialName("sanction_kind") val sanctionKind: String,
    val reason: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("appeal_state") val appealState: String? = null,
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

    suspend fun searchProfiles(query: String, minimumAge: Int, maximumAge: Int, verifiedOnly: Boolean, hasPhotoOnly: Boolean): List<RemoteProfile> = emptyList()

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

    suspend fun favoriteProfiles(pageSize: Int = 50): List<RemoteProfile> = emptyList()

    suspend fun setProfileFavorite(targetUserId: String, favorite: Boolean): Boolean = favorite

    suspend fun hideProfile(targetUserId: String): Boolean = false

    suspend fun unhideProfile(targetUserId: String): Boolean = false

    suspend fun setActivityVisibility(visible: Boolean): Boolean = visible

    suspend fun privacyCenter(): PrivacyCenter = PrivacyCenter()

    suspend fun unblockUser(targetUserId: String): Boolean = false

    suspend fun activeSanction(): ActiveSanction? = null

    suspend fun submitModerationAppeal(sanctionId: String, statement: String): String =
        error("Moderation appeals are not supported")

    suspend fun requestAccountDeletion(): Boolean = false

    suspend fun updateMyProfile(displayName: String, bio: String, intent: String): Boolean = false

    suspend fun updateMyInterests(interests: List<String>): Boolean = false

    suspend fun exportMyAccountData(): String = error("Account export is not supported")
}

class SupabaseProfileGateway(
    private val client: SupabaseClient,
) : ProfileGateway {
    override suspend fun updateMyProfile(displayName: String, bio: String, intent: String): Boolean =
        client.postgrest.rpc(
            "update_my_profile",
            buildJsonObject {
                put("display_name", displayName.trim())
                put("bio", bio.trim())
                put("intent", intent.trim())
            },
        ).decodeAs()

    override suspend fun updateMyInterests(interests: List<String>): Boolean {
        require(interests.size <= 8 && interests.distinct().size == interests.size)
        return client.postgrest.rpc(
            "update_my_interests",
            buildJsonObject { put("selected_interests", interests.toJsonArray()) },
        ).decodeAs()
    }

    override suspend fun exportMyAccountData(): String = client.postgrest
        .rpc("export_my_account_data").decodeAs<kotlinx.serialization.json.JsonObject>().toString()

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
        val interests = client.postgrest.rpc("get_my_interests").decodeAs<List<String>>()
        val photoState = client.postgrest
            .rpc("get_my_profile_photo_state")
            .decodeList<ProfilePhotoState>()
            .singleOrNull()
        return profile.copy(
            genderIdentityIds = settings.genderIdentityIds,
            genderSelfDescription = settings.genderSelfDescription,
            genderVisible = settings.genderVisible,
            interests = interests,
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

    override suspend fun searchProfiles(
        query: String,
        minimumAge: Int,
        maximumAge: Int,
        verifiedOnly: Boolean,
        hasPhotoOnly: Boolean,
    ): List<RemoteProfile> = enrichWithInterests(client.postgrest.rpc(
        "search_discovery_profiles",
        buildJsonObject {
            put("search_text", query.trim())
            put("minimum_age", minimumAge)
            put("maximum_age", maximumAge)
            put("verified_only", verifiedOnly)
            put("has_photo_only", hasPhotoOnly)
            put("page_size", 50)
        },
    ).decodeList<DiscoveryProfileRow>().map { it.toRemoteProfile().withApprovedAvatar() })

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
        if (cursor == null) {
            client.postgrest.rpc("touch_profile_presence").decodeAs<Boolean>()
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
            profiles = enrichWithInterests(rows.map { it.toRemoteProfile().withApprovedAvatar() }),
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

    override suspend fun favoriteProfiles(pageSize: Int): List<RemoteProfile> {
        require(pageSize in 1..100)
        return enrichWithInterests(client.postgrest.rpc(
            "get_favorite_profiles",
            buildJsonObject { put("page_size", pageSize) },
        ).decodeList<DiscoveryProfileRow>().map { it.toRemoteProfile().withApprovedAvatar() })
    }

    private suspend fun enrichWithInterests(profiles: List<RemoteProfile>): List<RemoteProfile> {
        if (profiles.isEmpty()) return profiles
        val byProfile = client.postgrest.rpc(
            "get_public_profile_interests",
            buildJsonObject { put("target_ids", profiles.map(RemoteProfile::id).toJsonArray()) },
        ).decodeList<PublicProfileInterestsRow>().associate { it.profileId to it.interests }
        return profiles.map { profile -> profile.copy(interests = byProfile[profile.id].orEmpty()) }
    }

    override suspend fun setProfileFavorite(targetUserId: String, favorite: Boolean): Boolean {
        requireUuid(targetUserId)
        return client.postgrest.rpc(
            "set_profile_favorite",
            buildJsonObject {
                put("target_user_id", targetUserId)
                put("should_favorite", favorite)
            },
        ).decodeAs()
    }

    override suspend fun hideProfile(targetUserId: String): Boolean {
        requireUuid(targetUserId)
        return client.postgrest.rpc(
            "hide_profile",
            buildJsonObject { put("target_user_id", targetUserId) },
        ).decodeAs()
    }

    override suspend fun unhideProfile(targetUserId: String): Boolean {
        requireUuid(targetUserId)
        return client.postgrest.rpc(
            "unhide_profile",
            buildJsonObject { put("target_user_id", targetUserId) },
        ).decodeAs()
    }

    override suspend fun setActivityVisibility(visible: Boolean): Boolean = client.postgrest.rpc(
        "set_activity_visibility",
        buildJsonObject { put("visible", visible) },
    ).decodeAs()

    override suspend fun privacyCenter(): PrivacyCenter {
        val settings = client.postgrest.rpc("get_privacy_settings")
            .decodeList<PrivacySettings>().singleOrNull() ?: PrivacySettings()
        val hidden = client.postgrest.rpc(
            "list_hidden_profiles", buildJsonObject { put("page_size", 100) },
        ).decodeList<DiscoveryProfileRow>().map { it.toRemoteProfile().withApprovedAvatar() }
        val blocked = client.postgrest.rpc(
            "list_blocked_profiles", buildJsonObject { put("page_size", 100) },
        ).decodeList<DiscoveryProfileRow>().map { it.toRemoteProfile().withApprovedAvatar() }
        return PrivacyCenter(settings, hidden, blocked)
    }

    override suspend fun unblockUser(targetUserId: String): Boolean {
        requireUuid(targetUserId)
        return client.postgrest.rpc(
            "unblock_user", buildJsonObject { put("target_user_id", targetUserId) },
        ).decodeAs()
    }

    override suspend fun activeSanction(): ActiveSanction? = client.postgrest
        .rpc("get_my_active_sanction").decodeList<ActiveSanction>().singleOrNull()

    override suspend fun submitModerationAppeal(sanctionId: String, statement: String): String {
        requireUuid(sanctionId)
        require(statement.trim().length in 20..2000)
        return client.postgrest.rpc(
            "submit_moderation_appeal",
            buildJsonObject {
                put("target_sanction_id", sanctionId)
                put("appeal_statement", statement.trim())
            },
        ).decodeAs()
    }

    private suspend fun RemoteProfile.withOwnerPhotoState(
        state: ProfilePhotoState?,
    ): RemoteProfile {
        val moderationStatus = when {
            state?.moderationStatus == "pending" && state.automationState == "review" -> "review"
            else -> state?.moderationStatus ?: "none"
        }
        val renderPath = when (moderationStatus) {
            "pending", "review" -> state?.candidatePath
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
    @SerialName("automation_state") val automationState: String = "completed",
    @SerialName("approved_path") val approvedPath: String? = null,
)

@Serializable
private data class PublicProfileInterestsRow(
    @SerialName("profile_id") val profileId: String,
    val interests: List<String>,
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
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("activity_status") val activityStatus: String? = null,
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
        isFavorite = isFavorite,
        activityStatus = activityStatus,
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
