package com.matcher.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionStatus
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
        client.auth.signInWith(OTP) {
            this.email = email.trim()
            createUser = true
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
