package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.call.body
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject

enum class AgeVerificationStatus {
    NotStarted,
    Pending,
    Verified,
    Failed,
    ManualReview,
    Unknown,
}

data class AgeVerificationSnapshot(
    val accountStatus: String,
    val verificationStatus: AgeVerificationStatus,
    val onboardingComplete: Boolean,
    val verificationMethod: String? = null,
    val verifiedAt: String? = null,
)

data class AgeVerificationSession(
    val verificationUrl: String,
    val expiresAt: String,
)

interface AgeVerificationGateway {
    suspend fun getStatus(): AgeVerificationSnapshot

    suspend fun createSession(): AgeVerificationSession
}

@Serializable
private data class AgeVerificationStatusRow(
    @SerialName("account_status") val accountStatus: String,
    @SerialName("verification_status") val verificationStatus: String,
    @SerialName("onboarding_complete") val onboardingComplete: Boolean,
    @SerialName("verification_method") val verificationMethod: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
)

@Serializable
private data class AgeVerificationSessionResponse(
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_at") val expiresAt: String,
)

class SupabaseAgeVerificationGateway(
    private val client: SupabaseClient,
) : AgeVerificationGateway {
    override suspend fun getStatus(): AgeVerificationSnapshot {
        val row = client.postgrest
            .rpc("get_age_verification_status")
            .decodeSingle<AgeVerificationStatusRow>()
        return AgeVerificationSnapshot(
            accountStatus = row.accountStatus,
            verificationStatus = row.verificationStatus.toAgeVerificationStatus(),
            onboardingComplete = row.onboardingComplete,
            verificationMethod = row.verificationMethod,
            verifiedAt = row.verifiedAt,
        )
    }

    override suspend fun createSession(): AgeVerificationSession {
        val response = client.functions
            .invoke(
                function = "age-verification-session",
                body = buildJsonObject {},
                headers = Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                },
            )
            .body<AgeVerificationSessionResponse>()
        return AgeVerificationSession(
            verificationUrl = response.verificationUrl,
            expiresAt = response.expiresAt,
        )
    }
}

private fun String.toAgeVerificationStatus(): AgeVerificationStatus = when (this) {
    "not_started" -> AgeVerificationStatus.NotStarted
    "pending" -> AgeVerificationStatus.Pending
    "verified" -> AgeVerificationStatus.Verified
    "failed" -> AgeVerificationStatus.Failed
    "manual_review" -> AgeVerificationStatus.ManualReview
    else -> AgeVerificationStatus.Unknown
}
