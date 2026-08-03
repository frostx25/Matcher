package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface MatcherSession {
    data object Loading : MatcherSession
    data object SignedOut : MatcherSession
    data class SignedIn(val userId: String) : MatcherSession
    data object RefreshFailed : MatcherSession
}

interface AuthGateway {
    val session: Flow<MatcherSession>

    suspend fun requestEmailOtp(email: String)

    suspend fun verifyEmailOtp(email: String, token: String)

    suspend fun signOut()
}

enum class EmailOtpRequestFailure {
    RateLimited,
    DeliveryUnknown,
    NetworkUnavailable,
    ProviderRejected,
}

class EmailOtpRequestException(
    val failure: EmailOtpRequestFailure,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception("Email OTP request failed", cause)

class SupabaseAuthGateway(
    private val client: SupabaseClient,
) : AuthGateway {
    override val session: Flow<MatcherSession> = client.auth.sessionStatus.map { status ->
        when (status) {
            SessionStatus.Initializing -> MatcherSession.Loading
            is SessionStatus.Authenticated -> MatcherSession.SignedIn(
                requireNotNull(status.session.user).id,
            )
            is SessionStatus.NotAuthenticated -> MatcherSession.SignedOut
            is SessionStatus.RefreshFailure -> MatcherSession.RefreshFailed
        }
    }

    override suspend fun requestEmailOtp(email: String) {
        try {
            client.auth.signInWith(OTP) {
                this.email = email.trim()
                createUser = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EmailOtpRequestException(
                failure = error.toEmailOtpRequestFailure(),
                retryAfterSeconds = error.retryAfterSeconds(),
                cause = error,
            )
        }
    }

    override suspend fun verifyEmailOtp(email: String, token: String) {
        client.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = email.trim(),
            token = token.trim(),
        )
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}

internal fun Throwable.toEmailOtpRequestFailure(): EmailOtpRequestFailure = when (this) {
    is EmailOtpRequestException -> failure
    is AuthRestException -> when {
        errorCode == AuthErrorCode.OverEmailSendRateLimit ||
            errorCode == AuthErrorCode.OverRequestRateLimit -> EmailOtpRequestFailure.RateLimited
        errorCode == AuthErrorCode.RequestTimeout -> EmailOtpRequestFailure.DeliveryUnknown
        statusCode == 429 -> EmailOtpRequestFailure.RateLimited
        statusCode == 408 || statusCode == 504 -> EmailOtpRequestFailure.DeliveryUnknown
        else -> EmailOtpRequestFailure.ProviderRejected
    }
    is RestException -> when (statusCode) {
        429 -> EmailOtpRequestFailure.RateLimited
        408, 504 -> EmailOtpRequestFailure.DeliveryUnknown
        else -> EmailOtpRequestFailure.ProviderRejected
    }
    is HttpRequestTimeoutException,
    is SocketTimeoutException -> EmailOtpRequestFailure.DeliveryUnknown
    is HttpRequestException -> EmailOtpRequestFailure.NetworkUnavailable
    is ResponseException -> when (response.status.value) {
        429 -> EmailOtpRequestFailure.RateLimited
        408, 504 -> EmailOtpRequestFailure.DeliveryUnknown
        else -> EmailOtpRequestFailure.ProviderRejected
    }
    is IOException -> EmailOtpRequestFailure.NetworkUnavailable
    else -> EmailOtpRequestFailure.ProviderRejected
}

private fun Throwable.retryAfterSeconds(): Int? = (this as? RestException)
    ?.response
    ?.headers
    ?.get(HttpHeaders.RetryAfter)
    ?.trim()
    ?.toIntOrNull()
    ?.coerceIn(1, 3_600)
