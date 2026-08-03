package com.matcher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.matcher.app.data.remote.AgeVerificationGateway
import com.matcher.app.data.remote.AgeVerificationStatus
import com.matcher.app.data.remote.AuthGateway
import com.matcher.app.data.remote.CompleteOnboardingRequest
import com.matcher.app.data.remote.DiscoveryPage
import com.matcher.app.data.remote.GenderSettings
import com.matcher.app.data.remote.MatcherSession
import com.matcher.app.data.remote.PrivateAlbum
import com.matcher.app.data.remote.PrivateAlbumGateway
import com.matcher.app.data.remote.PrivateAlbumGrant
import com.matcher.app.data.remote.PrivateAlbumItem
import com.matcher.app.data.remote.PrivateAlbumReportReason
import com.matcher.app.data.remote.ProfileGateway
import com.matcher.app.data.remote.RemoteChatGateway
import com.matcher.app.data.remote.RemoteProfile
import com.matcher.app.data.remote.SharedPrivateAlbum
import com.matcher.app.data.remote.UpdateGenderSettingsRequest
import com.matcher.app.data.remote.matcherCode
import com.matcher.app.domain.chat.ChatSnapshot
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.domain.chat.SendMessageResult
import com.matcher.app.domain.chat.StartConversationResult
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val EMAIL_OTP_LENGTH = 6

enum class SignedInStage {
    Resolving,
    Onboarding,
    Active,
    Unavailable,
}

sealed interface RemoteMatcherEffect {
    data class OpenAgeVerification(val url: String) : RemoteMatcherEffect
}

sealed interface PrivateAlbumDestination {
    data object Mine : PrivateAlbumDestination

    data class Warning(
        val albumId: String,
        val ownerId: String,
        val ownerName: String,
    ) : PrivateAlbumDestination

    data class Received(
        val albumId: String,
        val ownerId: String,
        val ownerName: String,
    ) : PrivateAlbumDestination
}

data class PrivateAlbumUiState(
    val destination: PrivateAlbumDestination? = null,
    val myAlbum: PrivateAlbum? = null,
    val myItems: List<PrivateAlbumItem> = emptyList(),
    val myGrants: List<PrivateAlbumGrant> = emptyList(),
    val knownRecipients: Map<String, String> = emptyMap(),
    val sharedWithMe: List<SharedPrivateAlbum> = emptyList(),
    val visibleItems: List<PrivateAlbumItem> = emptyList(),
    val visibleBytes: Map<String, ByteArray> = emptyMap(),
    val loading: Boolean = false,
)

private data class PrivateAlbumWorkToken(
    val sessionGeneration: Long,
    val albumGeneration: Long,
    val userId: String,
)

private data class SessionWorkToken(
    val generation: Long,
    val userId: String?,
)

data class RemoteMatcherUiState(
    val session: MatcherSession = MatcherSession.Loading,
    val signedInStage: SignedInStage = SignedInStage.Resolving,
    val ageVerificationStatus: AgeVerificationStatus = AgeVerificationStatus.NotStarted,
    val ageVerificationConsentGranted: Boolean = false,
    val ageVerificationOpen: Boolean = false,
    val otpRequestedFor: String? = null,
    val profile: RemoteProfile? = null,
    val genderSettings: GenderSettings? = null,
    val privateAlbum: PrivateAlbumUiState = PrivateAlbumUiState(),
    val discovery: DiscoveryPage = DiscoveryPage(emptyList(), null),
    val chat: ChatSnapshot = ChatSnapshot(0, emptyList(), emptySet(), emptyList()),
    val loading: Boolean = false,
    val verificationLoading: Boolean = false,
    val photoLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    companion object {
        val EmptyDiscovery = DiscoveryPage(emptyList(), null)
        val EmptyChat = ChatSnapshot(0, emptyList(), emptySet(), emptyList())
    }
}

class RemoteMatcherViewModel(
    private val authGateway: AuthGateway,
    private val profileGateway: ProfileGateway,
    private val chatGateway: RemoteChatGateway,
    private val privateAlbumGateway: PrivateAlbumGateway,
    private val ageVerificationGateway: AgeVerificationGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteMatcherUiState())
    val uiState: StateFlow<RemoteMatcherUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<RemoteMatcherEffect>(Channel.BUFFERED)
    val effects: Flow<RemoteMatcherEffect> = effectChannel.receiveAsFlow()

    private var realtimeJob: Job? = null
    private var ageVerificationJob: Job? = null
    private var profilePhotoJob: Job? = null
    private var discoveryPaginationJob: Job? = null
    private var privateAlbumJob: Job? = null
    private var privateAlbumSummaryJob: Job? = null
    private var albumRevalidationJob: Job? = null
    private var sessionGeneration = 0L
    private var privateAlbumGeneration = 0L
    private var lastAgeVerificationReturnSignal = 0
    private var ageVerificationRefreshPending = false

    init {
        viewModelScope.launch {
            authGateway.session.collectLatest { session ->
                sessionGeneration += 1
                invalidatePrivateAlbumWork()
                wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
                realtimeJob?.cancel()
                realtimeJob = null
                discoveryPaginationJob?.cancel()
                discoveryPaginationJob = null
                ageVerificationJob?.cancel()
                ageVerificationJob = null
                profilePhotoJob?.cancel()
                profilePhotoJob = null
                ageVerificationRefreshPending = false
                mutableState.update { current ->
                    current.copy(
                        session = session,
                        otpRequestedFor = if (session is MatcherSession.SignedOut) {
                            current.otpRequestedFor
                        } else {
                            null
                        },
                        signedInStage = SignedInStage.Resolving,
                        ageVerificationStatus = AgeVerificationStatus.NotStarted,
                        ageVerificationConsentGranted = false,
                        ageVerificationOpen = false,
                        profile = null,
                        genderSettings = null,
                        privateAlbum = PrivateAlbumUiState(),
                        discovery = RemoteMatcherUiState.EmptyDiscovery,
                        chat = RemoteMatcherUiState.EmptyChat,
                        loading = false,
                        verificationLoading = false,
                        photoLoading = false,
                        errorMessage = when (session) {
                            MatcherSession.RefreshFailed -> "Sua sessão expirou. Entre novamente."
                            is MatcherSession.SignedIn -> null
                            else -> current.errorMessage
                        },
                    )
                }
                if (session is MatcherSession.SignedIn) {
                    refreshSignedInData(currentSessionWorkToken())
                }
            }
        }
    }

    fun requestOtp(email: String) {
        val normalized = email.trim()
        if (!normalized.contains('@') || normalized.length > 254) {
            setError("Digite um e-mail válido.")
            return
        }
        launchRemote { token ->
            authGateway.requestEmailOtp(normalized)
            ensureSessionWorkIsCurrent(token)
            mutableState.update { it.copy(otpRequestedFor = normalized) }
        }
    }

    fun verifyOtp(email: String, token: String) {
        val normalizedToken = token.trim()
        if (normalizedToken.length != EMAIL_OTP_LENGTH || normalizedToken.any { !it.isDigit() }) {
            setError("Digite o código de 6 dígitos.")
            return
        }
        launchRemote(errorMessage = "Código inválido ou expirado. Solicite um novo código.") { token ->
            authGateway.verifyEmailOtp(email, normalizedToken)
            ensureSessionWorkIsCurrent(token)
        }
    }

    fun changeOtpEmail() {
        mutableState.update { it.copy(otpRequestedFor = null, errorMessage = null) }
    }

    fun completeOnboarding(
        displayName: String,
        birthYear: Int,
        bio: String,
        intent: String,
        genderIdentityIds: Set<String>,
        genderSelfDescription: String,
        genderVisible: Boolean,
        lookingForGenderIds: Set<String>,
        termsAccepted: Boolean,
    ) {
        launchRemote { token ->
            profileGateway.completeOnboarding(
                CompleteOnboardingRequest(
                    birthYear = birthYear,
                    displayName = displayName.trim(),
                    regionCode = DevelopmentRegion,
                    termsVersion = TermsVersion,
                    termsAccepted = termsAccepted,
                    bio = bio.trim(),
                    intent = intent.trim().ifBlank { "Conhecer pessoas" },
                    genderIdentityIds = genderIdentityIds.sorted(),
                    genderSelfDescription = genderSelfDescription.trim().ifBlank { null },
                    genderVisible = genderVisible,
                    lookingForGenderIds = lookingForGenderIds.sorted(),
                ),
            )
            ensureSessionWorkIsCurrent(token)
            refreshSignedInData(token)
        }
    }

    fun updateGenderSettings(
        genderIdentityIds: Set<String>,
        genderSelfDescription: String,
        genderVisible: Boolean,
        lookingForGenderIds: Set<String>,
    ) {
        if (!requireActiveAccount()) return
        if (genderIdentityIds.isEmpty() || lookingForGenderIds.isEmpty()) {
            setError("Escolha sua identidade e quem você quer encontrar.")
            return
        }
        launchRemote { token ->
            val settings = profileGateway.updateGenderSettings(
                UpdateGenderSettingsRequest(
                    genderIdentityIds = genderIdentityIds.sorted(),
                    genderSelfDescription = genderSelfDescription.trim().ifBlank { null },
                    genderVisible = genderVisible,
                    lookingForGenderIds = lookingForGenderIds.sorted(),
                ),
            )
            ensureSessionWorkIsCurrent(token)
            val discovery = profileGateway.discoveryPage()
            ensureSessionWorkIsCurrent(token)
            mutableState.update { state ->
                state.copy(
                    genderSettings = settings,
                    profile = state.profile?.copy(
                        genderIdentityIds = settings.genderIdentityIds,
                        genderSelfDescription = settings.genderSelfDescription,
                        genderVisible = settings.genderVisible,
                    ),
                    discovery = discovery,
                )
            }
        }
    }

    fun loadMoreDiscovery() {
        val state = mutableState.value
        val cursor = state.discovery.nextCursor ?: return
        if (!requireActiveAccount() || state.loading) return
        val token = currentSessionWorkToken()
        mutableState.update { it.copy(loading = true, errorMessage = null) }
        discoveryPaginationJob = viewModelScope.launch {
            var restartedAfterStaleCursor = false
            try {
                val nextPage = try {
                    profileGateway.discoveryPage(
                        cursor = cursor,
                        preferenceCursorVersion = state.discovery.preferenceCursorVersion,
                    )
                } catch (error: Exception) {
                    if (error.matcherCode() == "DISCOVERY_CURSOR_STALE") {
                        ensureSessionWorkIsCurrent(token)
                        restartedAfterStaleCursor = true
                        profileGateway.discoveryPage()
                    } else {
                        throw error
                    }
                }
                ensureSessionWorkIsCurrent(token)
                mutableState.update { current ->
                    if (!isSessionWorkCurrent(token)) return@update current
                    val profiles = if (restartedAfterStaleCursor) {
                        nextPage.profiles
                    } else {
                        (current.discovery.profiles + nextPage.profiles).distinctBy(RemoteProfile::id)
                    }
                    current.copy(discovery = nextPage.copy(profiles = profiles))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) setError(error.toUserMessage())
            } finally {
                if (isSessionWorkCurrent(token)) {
                    mutableState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun setAgeVerificationConsent(granted: Boolean) {
        val state = mutableState.value
        if (state.session !is MatcherSession.SignedIn ||
            state.signedInStage != SignedInStage.Active ||
            !state.ageVerificationOpen
        ) {
            return
        }
        mutableState.update {
            it.copy(
                ageVerificationConsentGranted = granted,
                errorMessage = if (granted && it.errorMessage == AGE_VERIFICATION_CONSENT_REQUIRED_MESSAGE) {
                    null
                } else {
                    it.errorMessage
                },
            )
        }
    }

    fun openAgeVerification() {
        val state = mutableState.value
        if (state.session !is MatcherSession.SignedIn ||
            state.signedInStage != SignedInStage.Active
        ) {
            return
        }
        mutableState.update {
            it.copy(ageVerificationOpen = true, errorMessage = null)
        }
    }

    fun closeAgeVerification() {
        mutableState.update {
            it.copy(
                ageVerificationOpen = false,
                ageVerificationConsentGranted = false,
                errorMessage = null,
            )
        }
    }

    fun startAgeVerification() {
        val state = mutableState.value
        if (state.session !is MatcherSession.SignedIn ||
            state.signedInStage != SignedInStage.Active ||
            !state.ageVerificationOpen ||
            state.verificationLoading
        ) {
            return
        }
        if (!state.ageVerificationConsentGranted) {
            setError(AGE_VERIFICATION_CONSENT_REQUIRED_MESSAGE)
            return
        }

        val token = currentSessionWorkToken()
        mutableState.update { it.copy(verificationLoading = true, errorMessage = null) }
        ageVerificationJob = viewModelScope.launch {
            try {
                val session = ageVerificationGateway.createSession()
                ensureSessionWorkIsCurrent(token)
                check(isTrustedAgeVerificationUrl(session.verificationUrl)) {
                    "AGE_PROVIDER_INVALID_URL"
                }
                mutableState.update {
                    it.copy(ageVerificationStatus = AgeVerificationStatus.Pending)
                }
                ensureSessionWorkIsCurrent(token)
                effectChannel.send(RemoteMatcherEffect.OpenAgeVerification(session.verificationUrl))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) {
                    if (error.matcherCode() == "ALREADY_VERIFIED") {
                        refreshSignedInData(token)
                    } else {
                        setError(error.toUserMessage())
                    }
                }
            } finally {
                if (isSessionWorkCurrent(token)) {
                    mutableState.update { it.copy(verificationLoading = false) }
                    runPendingAgeVerificationRefresh()
                }
            }
        }
    }

    fun submitProfilePhoto(jpegBytes: ByteArray) {
        val state = mutableState.value
        if (state.session !is MatcherSession.SignedIn ||
            state.signedInStage != SignedInStage.Active ||
            state.photoLoading
        ) {
            return
        }
        val token = currentSessionWorkToken()
        mutableState.update { it.copy(photoLoading = true, errorMessage = null) }
        profilePhotoJob = viewModelScope.launch {
            try {
                val profile = profileGateway.submitProfilePhoto(jpegBytes)
                ensureSessionWorkIsCurrent(token)
                mutableState.update { current ->
                    if (isSessionWorkCurrent(token)) current.copy(profile = profile) else current
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) setError(error.toProfilePhotoMessage())
            } finally {
                if (isSessionWorkCurrent(token)) {
                    mutableState.update { it.copy(photoLoading = false) }
                }
            }
        }
    }

    fun reportProfilePhotoPreparationFailure() {
        setError("Não foi possível preparar essa imagem. Escolha uma foto JPG, PNG ou WebP válida.")
    }

    fun openMyPrivateAlbum() {
        if (!requireActiveAccount()) return
        beginPrivateAlbumDestination()
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = PrivateAlbumDestination.Mine,
                    visibleItems = emptyList(),
                    visibleBytes = emptyMap(),
                ),
                errorMessage = null,
            )
        }
        reloadMyPrivateAlbum(includeBytes = true)
    }

    fun refreshPrivateAlbumAccess() {
        if (!requireActiveAccount() || mutableState.value.privateAlbum.loading) return
        runPrivateAlbumOperation { token -> loadPrivateAlbumSummaries(token) }
    }

    fun openReceivedPrivateAlbum(album: SharedPrivateAlbum) {
        val current = mutableState.value.privateAlbum.sharedWithMe.firstOrNull {
            it.albumId == album.albumId && it.ownerId == album.ownerId
        }
        if (current == null) {
            setError("Este álbum privado não está mais liberado para você.")
            return
        }
        showReceivedPrivateAlbumWarning(current)
    }

    fun openReceivedPrivateAlbum(ownerId: String, ownerName: String) {
        if (!requireActiveAccount()) return
        val access = mutableState.value.privateAlbum.sharedWithMe.firstOrNull { it.ownerId == ownerId }
        if (access == null) {
            setError("Este álbum privado não está mais liberado para você.")
            return
        }
        showReceivedPrivateAlbumWarning(
            access.copy(ownerDisplayName = access.ownerDisplayName.ifBlank { ownerName }),
        )
    }

    private fun showReceivedPrivateAlbumWarning(access: SharedPrivateAlbum) {
        if (!requireActiveAccount()) return
        beginPrivateAlbumDestination()
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = PrivateAlbumDestination.Warning(
                        albumId = access.albumId,
                        ownerId = access.ownerId,
                        ownerName = access.ownerDisplayName,
                    ),
                    visibleItems = emptyList(),
                    visibleBytes = emptyMap(),
                ),
                errorMessage = null,
            )
        }
    }

    fun revealReceivedPrivateAlbum() {
        val warning = mutableState.value.privateAlbum.destination as? PrivateAlbumDestination.Warning
            ?: return
        runPrivateAlbumOperation { token ->
            val content = privateAlbumGateway.getPrivateAlbum(warning.albumId)
                ?: error("PRIVATE_ALBUM_NOT_FOUND")
            check(content.albumId == warning.albumId && content.ownerId == warning.ownerId) {
                "INVALID_PRIVATE_ALBUM_RESPONSE"
            }
            ensurePrivateAlbumWorkIsCurrent(token)
            val bytes = downloadPrivateAlbumItems(content.items, token)
            if (!isPrivateAlbumWorkCurrent(token) ||
                mutableState.value.privateAlbum.destination != warning
            ) {
                wipePrivateAlbumBytes(bytes)
                return@runPrivateAlbumOperation
            }
            replaceVisiblePrivateAlbumBytes(
                token = token,
                destination = PrivateAlbumDestination.Received(
                    albumId = warning.albumId,
                    ownerId = warning.ownerId,
                    ownerName = warning.ownerName,
                ),
                items = content.items,
                bytes = bytes,
            )
        }
    }

    fun closePrivateAlbum() {
        invalidatePrivateAlbumWork()
        wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = null,
                    visibleItems = emptyList(),
                    visibleBytes = emptyMap(),
                    loading = false,
                ),
                errorMessage = null,
            )
        }
    }

    fun revalidateReceivedPrivateAlbum() {
        val received = mutableState.value.privateAlbum.destination as? PrivateAlbumDestination.Received
            ?: return
        if (albumRevalidationJob?.isActive == true) return
        val token = currentPrivateAlbumWorkToken() ?: return
        albumRevalidationJob = viewModelScope.launch {
            try {
                val content = privateAlbumGateway.getPrivateAlbum(received.albumId)
                    ?: error("PRIVATE_ALBUM_NOT_AVAILABLE")
                check(content.albumId == received.albumId && content.ownerId == received.ownerId) {
                    "INVALID_PRIVATE_ALBUM_RESPONSE"
                }
                ensurePrivateAlbumWorkIsCurrent(token)
                val currentIds = mutableState.value.privateAlbum.visibleItems.map { it.itemId }
                val serverIds = content.items.map { it.itemId }
                if (currentIds != serverIds) {
                    val bytes = downloadPrivateAlbumItems(content.items, token)
                    if (!isPrivateAlbumWorkCurrent(token) ||
                        mutableState.value.privateAlbum.destination != received
                    ) {
                        wipePrivateAlbumBytes(bytes)
                        return@launch
                    }
                    replaceVisiblePrivateAlbumBytes(token, received, content.items, bytes)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isPrivateAlbumWorkCurrent(token)) {
                    clearVisiblePrivateAlbum()
                    setError(error.toPrivateAlbumMessage())
                }
            }
        }
    }

    fun uploadPrivateAlbumPhoto(jpegBytes: ByteArray) {
        val state = mutableState.value
        if (!requireActiveAccount() ||
            state.privateAlbum.destination != PrivateAlbumDestination.Mine ||
            state.privateAlbum.loading
        ) {
            jpegBytes.fill(0)
            return
        }
        val displayedAlbumId = state.privateAlbum.myAlbum?.albumId
        val scheduled = runPrivateAlbumOperation { token ->
            try {
                val targetAlbumId = displayedAlbumId ?: privateAlbumGateway.createPrivateAlbum(
                    contentPolicyVersion = PrivateAlbumContentPolicyVersion,
                    contentPolicyAccepted = true,
                ).albumId
                ensurePrivateAlbumWorkIsCurrent(token)
                privateAlbumGateway.uploadPrivateAlbumImage(targetAlbumId, jpegBytes)
                ensurePrivateAlbumWorkIsCurrent(token)
                loadMyPrivateAlbum(includeBytes = true, token = token)
            } finally {
                jpegBytes.fill(0)
            }
        }
        if (!scheduled) jpegBytes.fill(0)
    }

    fun deletePrivateAlbumPhoto(itemId: String) {
        if (mutableState.value.privateAlbum.destination != PrivateAlbumDestination.Mine) return
        runPrivateAlbumOperation { token ->
            privateAlbumGateway.deletePrivateAlbumImage(itemId)
            ensurePrivateAlbumWorkIsCurrent(token)
            loadMyPrivateAlbum(includeBytes = true, token = token)
        }
    }

    fun togglePrivateAlbumGrant(recipientId: String, currentlyShared: Boolean) {
        if (!requireActiveAccount()) return
        val expectedAlbumId = mutableState.value.privateAlbum.myAlbum?.albumId ?: return
        runPrivateAlbumOperation { token ->
            if (currentlyShared) {
                privateAlbumGateway.revokePrivateAlbumAccess(expectedAlbumId, recipientId)
            } else {
                privateAlbumGateway.grantPrivateAlbumAccess(expectedAlbumId, recipientId)
            }
            ensurePrivateAlbumWorkIsCurrent(token)
            loadPrivateAlbumSummaries(token)
        }
    }

    fun deleteMyPrivateAlbum() {
        val albumState = mutableState.value.privateAlbum
        if (albumState.destination != PrivateAlbumDestination.Mine) return
        val expectedAlbumId = albumState.myAlbum?.albumId ?: return
        runPrivateAlbumOperation { token ->
            privateAlbumGateway.deletePrivateAlbum(expectedAlbumId)
            ensurePrivateAlbumWorkIsCurrent(token)
            wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
            updatePrivateAlbumState(token) {
                it.copy(
                    privateAlbum = PrivateAlbumUiState(
                        destination = PrivateAlbumDestination.Mine,
                        sharedWithMe = it.privateAlbum.sharedWithMe,
                        knownRecipients = it.privateAlbum.knownRecipients,
                    ),
                )
            }
        }
    }

    fun reportPrivateAlbum(details: String) {
        val received = mutableState.value.privateAlbum.destination as? PrivateAlbumDestination.Received
            ?: return
        val sessionToken = currentSessionWorkToken()
        runPrivateAlbumOperation { token ->
            privateAlbumGateway.reportPrivateAlbum(
                albumId = received.albumId,
                reason = PrivateAlbumReportReason.InappropriatePhoto,
                details = details,
            )
            ensurePrivateAlbumWorkIsCurrent(token)
            ensureSessionWorkIsCurrent(sessionToken)
            clearVisiblePrivateAlbum()
            refreshSignedInData(sessionToken)
        }
    }

    fun refreshAgeVerificationStatus() {
        val state = mutableState.value
        if (state.session !is MatcherSession.SignedIn) return
        if (state.verificationLoading) {
            ageVerificationRefreshPending = true
            return
        }
        val token = currentSessionWorkToken()
        mutableState.update { it.copy(verificationLoading = true, errorMessage = null) }
        ageVerificationJob = viewModelScope.launch {
            try {
                refreshSignedInData(token)
            } finally {
                if (isSessionWorkCurrent(token)) {
                    mutableState.update { it.copy(verificationLoading = false) }
                    runPendingAgeVerificationRefresh()
                }
            }
        }
    }

    /** A provider redirect is only a refresh signal; it never carries an authorization result. */
    fun onAgeVerificationReturn(signal: Int) {
        if (signal <= 0 || signal <= lastAgeVerificationReturnSignal) return
        lastAgeVerificationReturnSignal = signal
        refreshAgeVerificationStatus()
    }

    fun startConversation(recipientId: String, message: String, onOpened: (String) -> Unit) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            when (val result = chatGateway.startConversation(recipientId, message)) {
                is StartConversationResult.Created -> {
                    ensureSessionWorkIsCurrent(token)
                    reloadChat(token)
                    ensureSessionWorkIsCurrent(token)
                    onOpened(result.conversation.id)
                }
                is StartConversationResult.Existing -> {
                    ensureSessionWorkIsCurrent(token)
                    reloadChat(token)
                    ensureSessionWorkIsCurrent(token)
                    onOpened(result.conversation.id)
                }
                is StartConversationResult.QuotaExhausted -> {
                    ensureSessionWorkIsCurrent(token)
                    setError("Seu limite de novas conversas foi atingido. Conversas existentes continuam liberadas.")
                }
                is StartConversationResult.InvalidMessage -> {
                    ensureSessionWorkIsCurrent(token)
                    setError("Escreva uma mensagem válida antes de enviar.")
                }
                is StartConversationResult.Blocked -> {
                    ensureSessionWorkIsCurrent(token)
                    setError("Este contato não está disponível.")
                }
            }
        }
    }

    fun sendMessage(conversationId: String, body: String): Boolean {
        if (body.isBlank() || !requireActiveAccount()) return false
        launchRemote { token ->
            when (chatGateway.sendMessage(conversationId, body)) {
                is SendMessageResult.Sent -> {
                    ensureSessionWorkIsCurrent(token)
                    reloadChat(token)
                }
                SendMessageResult.InvalidMessage -> {
                    ensureSessionWorkIsCurrent(token)
                    setError("Escreva uma mensagem válida antes de enviar.")
                }
                SendMessageResult.NotAllowed,
                SendMessageResult.NotFound -> {
                    ensureSessionWorkIsCurrent(token)
                    setError("Esta conversa não está mais disponível.")
                }
            }
        }
        return true
    }

    fun blockUser(targetUserId: String, onBlocked: () -> Unit) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            if (chatGateway.blockUser(targetUserId)) {
                ensureSessionWorkIsCurrent(token)
                invalidatePrivateAlbumWork()
                clearVisiblePrivateAlbum()
                refreshSignedInData(token)
                onBlocked()
            } else {
                setError("Não foi possível bloquear este perfil.")
            }
        }
    }

    fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
        onReported: () -> Unit,
    ) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            chatGateway.reportUser(targetUserId, reason, details, conversationId)
            ensureSessionWorkIsCurrent(token)
            invalidatePrivateAlbumWork()
            clearVisiblePrivateAlbum()
            refreshSignedInData(token)
            onReported()
        }
    }

    fun signOut() {
        discoveryPaginationJob?.cancel()
        discoveryPaginationJob = null
        profilePhotoJob?.cancel()
        profilePhotoJob = null
        invalidatePrivateAlbumWork()
        wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
        mutableState.update { it.copy(privateAlbum = PrivateAlbumUiState()) }
        launchRemote { authGateway.signOut() }
    }

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    private suspend fun refreshSignedInData(token: SessionWorkToken) {
        ensureSessionWorkIsCurrent(token)
        val previousState = mutableState.value
        val canPreserveActiveScreen = previousState.signedInStage == SignedInStage.Active &&
            previousState.profile != null
        if (canPreserveActiveScreen) {
            mutableState.update { it.copy(loading = true, errorMessage = null) }
        } else {
            realtimeJob?.cancel()
            realtimeJob = null
            mutableState.update {
                it.copy(
                    signedInStage = SignedInStage.Resolving,
                    profile = null,
                    genderSettings = null,
                    privateAlbum = PrivateAlbumUiState(),
                    discovery = RemoteMatcherUiState.EmptyDiscovery,
                    chat = RemoteMatcherUiState.EmptyChat,
                    loading = true,
                    errorMessage = null,
                )
            }
        }
        try {
            val access = ageVerificationGateway.getStatus()
            ensureSessionWorkIsCurrent(token)
            mutableState.update { it.copy(ageVerificationStatus = access.verificationStatus) }

            when {
                access.accountStatus == "suspended" || access.accountStatus == "deleted" -> {
                    realtimeJob?.cancel()
                    realtimeJob = null
                    mutableState.update {
                        it.copy(
                            signedInStage = SignedInStage.Unavailable,
                            errorMessage = "Esta conta não está disponível.",
                        )
                    }
                }

                !access.onboardingComplete -> {
                    realtimeJob?.cancel()
                    realtimeJob = null
                    mutableState.update { it.copy(signedInStage = SignedInStage.Onboarding) }
                }

                access.accountStatus == "active" -> loadActiveAccount(token)

                else -> {
                    realtimeJob?.cancel()
                    realtimeJob = null
                    mutableState.update {
                        it.copy(
                            signedInStage = SignedInStage.Unavailable,
                            errorMessage = "Não foi possível confirmar o acesso desta conta.",
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isSessionWorkCurrent(token)) {
                mutableState.update {
                    if (canPreserveActiveScreen) {
                        it.copy(
                            signedInStage = SignedInStage.Active,
                            errorMessage = error.toUserMessage(),
                        )
                    } else {
                        it.copy(
                            signedInStage = SignedInStage.Unavailable,
                            errorMessage = error.toUserMessage(),
                        )
                    }
                }
            }
        } finally {
            if (isSessionWorkCurrent(token)) {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun loadActiveAccount(token: SessionWorkToken) {
        val profile = profileGateway.currentProfile()
            ?: error("ACTIVE_PROFILE_MISSING")
        ensureSessionWorkIsCurrent(token)
        val genderSettings = profileGateway.getGenderSettings()
        ensureSessionWorkIsCurrent(token)
        val discovery = profileGateway.discoveryPage()
        ensureSessionWorkIsCurrent(token)
        val chat = chatGateway.snapshot()
        ensureSessionWorkIsCurrent(token)
        realtimeJob?.cancel()
        realtimeJob = null
        mutableState.update {
            it.copy(
                signedInStage = SignedInStage.Active,
                profile = profile,
                genderSettings = genderSettings,
                discovery = discovery,
                chat = chat,
                ageVerificationOpen = if (
                    it.ageVerificationStatus == AgeVerificationStatus.Verified
                ) false else it.ageVerificationOpen,
                ageVerificationConsentGranted = if (
                    it.ageVerificationStatus == AgeVerificationStatus.Verified
                ) false else it.ageVerificationConsentGranted,
            )
        }
        startPrivateAlbumSummaryLoad()
        realtimeJob = viewModelScope.launch {
            try {
                chatGateway.realtimeInvalidations().collect { reloadChat(token) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) setError(error.toUserMessage())
            }
        }
    }

    private suspend fun reloadChat(token: SessionWorkToken = currentSessionWorkToken()) {
        if (mutableState.value.signedInStage != SignedInStage.Active) return
        val chat = chatGateway.snapshot()
        ensureSessionWorkIsCurrent(token)
        mutableState.update { current ->
            if (isSessionWorkCurrent(token)) current.copy(chat = chat) else current
        }
    }

    private fun reloadMyPrivateAlbum(includeBytes: Boolean) {
        runPrivateAlbumOperation { token -> loadMyPrivateAlbum(includeBytes, token) }
    }

    private fun startPrivateAlbumSummaryLoad() {
        val token = currentPrivateAlbumWorkToken() ?: return
        privateAlbumSummaryJob?.cancel()
        privateAlbumSummaryJob = viewModelScope.launch {
            try {
                loadPrivateAlbumSummaries(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isPrivateAlbumWorkCurrent(token)) setError(error.toPrivateAlbumMessage())
            }
        }
    }

    private suspend fun loadMyPrivateAlbum(
        includeBytes: Boolean,
        token: PrivateAlbumWorkToken,
    ) {
        loadPrivateAlbumSummaries(token)
        ensurePrivateAlbumWorkIsCurrent(token)
        val albumState = mutableState.value.privateAlbum
        val bytes = if (includeBytes) {
            downloadPrivateAlbumItems(albumState.myItems, token)
        } else {
            emptyMap()
        }
        if (!isPrivateAlbumWorkCurrent(token) ||
            mutableState.value.privateAlbum.destination != PrivateAlbumDestination.Mine
        ) {
            wipePrivateAlbumBytes(bytes)
            return
        }
        replaceVisiblePrivateAlbumBytes(
            token = token,
            destination = PrivateAlbumDestination.Mine,
            items = if (includeBytes) albumState.myItems else emptyList(),
            bytes = bytes,
        )
    }

    private suspend fun loadPrivateAlbumSummaries(token: PrivateAlbumWorkToken) {
        repeat(2) { attempt ->
            val album = privateAlbumGateway.getMyPrivateAlbum()
            ensurePrivateAlbumWorkIsCurrent(token)
            val items = if (album == null) emptyList() else privateAlbumGateway.getMyPrivateAlbumItems()
            ensurePrivateAlbumWorkIsCurrent(token)
            val grants = if (album == null) emptyList() else privateAlbumGateway.getMyPrivateAlbumGrants()
            ensurePrivateAlbumWorkIsCurrent(token)
            val sharedWithMe = privateAlbumGateway.listPrivateAlbumsSharedWithMe()
            ensurePrivateAlbumWorkIsCurrent(token)
            val validatedAlbum = privateAlbumGateway.getMyPrivateAlbum()
            ensurePrivateAlbumWorkIsCurrent(token)
            if (album?.albumId != validatedAlbum?.albumId) {
                if (attempt == 0) return@repeat
                error("PRIVATE_ALBUM_CHANGED")
            }
            updatePrivateAlbumState(token) {
                val knownRecipients = buildMap {
                    putAll(it.privateAlbum.knownRecipients)
                    grants.forEach { grant -> put(grant.recipientId, grant.displayName) }
                    sharedWithMe.forEach { shared -> put(shared.ownerId, shared.ownerDisplayName) }
                }
                it.copy(
                    privateAlbum = it.privateAlbum.copy(
                        myAlbum = validatedAlbum,
                        myItems = items,
                        myGrants = grants,
                        knownRecipients = knownRecipients,
                        sharedWithMe = sharedWithMe,
                    ),
                )
            }
            return
        }
    }

    private suspend fun downloadPrivateAlbumItems(
        items: List<PrivateAlbumItem>,
        token: PrivateAlbumWorkToken,
    ): Map<String, ByteArray> {
        val bytes = linkedMapOf<String, ByteArray>()
        var unownedDownload: ByteArray? = null
        try {
            for (item in items.filter { it.itemStatus == "available" }.sortedBy { it.position }) {
                ensurePrivateAlbumWorkIsCurrent(token)
                val downloaded = privateAlbumGateway.downloadPrivateAlbumImage(item.itemId)
                unownedDownload = downloaded
                if (downloaded.size !in 1..MaxPrivateAlbumImageBytes) {
                    downloaded.fill(0)
                    error("INVALID_PRIVATE_ALBUM_IMAGE_SIZE")
                }
                ensurePrivateAlbumWorkIsCurrent(token)
                bytes[item.itemId] = downloaded
                unownedDownload = null
            }
            return bytes
        } catch (error: Throwable) {
            unownedDownload?.fill(0)
            wipePrivateAlbumBytes(bytes)
            throw error
        }
    }

    private fun runPrivateAlbumOperation(
        block: suspend (PrivateAlbumWorkToken) -> Unit,
    ): Boolean {
        if (mutableState.value.privateAlbum.loading) return false
        val token = currentPrivateAlbumWorkToken() ?: return false
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(loading = true),
                errorMessage = null,
            )
        }
        privateAlbumJob = viewModelScope.launch {
            try {
                block(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isPrivateAlbumWorkCurrent(token)) {
                    if (error.matcherCode() in PrivateAlbumAccessErrors) clearVisiblePrivateAlbum()
                    setError(error.toPrivateAlbumMessage())
                }
            } finally {
                if (isPrivateAlbumWorkCurrent(token)) {
                    mutableState.update {
                        it.copy(privateAlbum = it.privateAlbum.copy(loading = false))
                    }
                }
            }
        }
        return true
    }

    private fun clearVisiblePrivateAlbum() {
        wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = null,
                    visibleItems = emptyList(),
                    visibleBytes = emptyMap(),
                ),
            )
        }
    }

    private fun beginPrivateAlbumDestination() {
        invalidatePrivateAlbumWork()
        wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
        mutableState.update {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = null,
                    visibleItems = emptyList(),
                    visibleBytes = emptyMap(),
                    loading = false,
                ),
            )
        }
    }

    private fun invalidatePrivateAlbumWork() {
        privateAlbumGeneration += 1
        privateAlbumJob?.cancel()
        privateAlbumJob = null
        privateAlbumSummaryJob?.cancel()
        privateAlbumSummaryJob = null
        albumRevalidationJob?.cancel()
        albumRevalidationJob = null
        mutableState.update {
            it.copy(privateAlbum = it.privateAlbum.copy(loading = false))
        }
    }

    private fun currentPrivateAlbumWorkToken(): PrivateAlbumWorkToken? {
        val userId = (mutableState.value.session as? MatcherSession.SignedIn)?.userId ?: return null
        return PrivateAlbumWorkToken(
            sessionGeneration = sessionGeneration,
            albumGeneration = privateAlbumGeneration,
            userId = userId,
        )
    }

    private fun isPrivateAlbumWorkCurrent(token: PrivateAlbumWorkToken): Boolean {
        val currentUserId = (mutableState.value.session as? MatcherSession.SignedIn)?.userId
        return token.sessionGeneration == sessionGeneration &&
            token.albumGeneration == privateAlbumGeneration &&
            token.userId == currentUserId
    }

    private fun ensurePrivateAlbumWorkIsCurrent(token: PrivateAlbumWorkToken) {
        if (!isPrivateAlbumWorkCurrent(token)) {
            throw CancellationException("Private album work is no longer current")
        }
    }

    private inline fun updatePrivateAlbumState(
        token: PrivateAlbumWorkToken,
        transform: (RemoteMatcherUiState) -> RemoteMatcherUiState,
    ) {
        if (isPrivateAlbumWorkCurrent(token)) mutableState.update(transform)
    }

    private fun replaceVisiblePrivateAlbumBytes(
        token: PrivateAlbumWorkToken,
        destination: PrivateAlbumDestination,
        items: List<PrivateAlbumItem>,
        bytes: Map<String, ByteArray>,
    ) {
        if (!isPrivateAlbumWorkCurrent(token)) {
            wipePrivateAlbumBytes(bytes)
            return
        }
        val previousBytes = mutableState.value.privateAlbum.visibleBytes
        updatePrivateAlbumState(token) {
            it.copy(
                privateAlbum = it.privateAlbum.copy(
                    destination = destination,
                    visibleItems = items,
                    visibleBytes = bytes,
                ),
            )
        }
        wipePrivateAlbumBytes(previousBytes)
    }

    private fun wipePrivateAlbumBytes(bytes: Map<String, ByteArray>) {
        bytes.values.forEach { value -> value.fill(0) }
    }

    private fun requireActiveAccount(): Boolean {
        if (mutableState.value.signedInStage == SignedInStage.Active) return true
        setError("Esta conta não pode usar esta função agora.")
        return false
    }

    private fun currentSessionWorkToken(): SessionWorkToken = SessionWorkToken(
        generation = sessionGeneration,
        userId = (mutableState.value.session as? MatcherSession.SignedIn)?.userId,
    )

    private fun isSessionWorkCurrent(token: SessionWorkToken): Boolean =
        token.generation == sessionGeneration &&
            (mutableState.value.session as? MatcherSession.SignedIn)?.userId == token.userId

    private fun ensureSessionWorkIsCurrent(token: SessionWorkToken) {
        if (!isSessionWorkCurrent(token)) {
            throw CancellationException("Session work is no longer current")
        }
    }

    private fun runPendingAgeVerificationRefresh() {
        if (!ageVerificationRefreshPending) return
        ageVerificationRefreshPending = false
        refreshAgeVerificationStatus()
    }

    private fun launchRemote(
        errorMessage: String? = null,
        block: suspend (SessionWorkToken) -> Unit,
    ) {
        val token = currentSessionWorkToken()
        viewModelScope.launch {
            if (!isSessionWorkCurrent(token)) return@launch
            mutableState.update { it.copy(loading = true, errorMessage = null) }
            try {
                block(token)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) {
                    if (error.matcherCode() == "ACCOUNT_NOT_ACTIVE") {
                        refreshSignedInData(token)
                    } else {
                        setError(errorMessage ?: error.toUserMessage())
                    }
                }
            } finally {
                if (isSessionWorkCurrent(token)) {
                    mutableState.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun setError(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    class Factory(
        private val authGateway: AuthGateway,
        private val profileGateway: ProfileGateway,
        private val chatGateway: RemoteChatGateway,
        private val privateAlbumGateway: PrivateAlbumGateway,
        private val ageVerificationGateway: AgeVerificationGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RemoteMatcherViewModel::class.java))
            return RemoteMatcherViewModel(
                authGateway,
                profileGateway,
                chatGateway,
                privateAlbumGateway,
                ageVerificationGateway,
            ) as T
        }
    }

    private companion object {
        const val TermsVersion = "dev-2026-07"
        const val DevelopmentRegion = "br-sao-paulo"
        const val PrivateAlbumContentPolicyVersion = "private-album-2026-07"
        const val MaxPrivateAlbumImageBytes = 5 * 1024 * 1024
    }
}

internal fun isTrustedAgeVerificationUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("verify.didit.me", ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)

internal const val AGE_VERIFICATION_CONSENT_REQUIRED_MESSAGE =
    "Autorize o processamento pela Didit antes de iniciar a verificação."

private fun Throwable.toUserMessage(): String = when (matcherCode()) {
    "ADULTS_ONLY" -> "O Matcher é exclusivo para pessoas com 18 anos ou mais."
    "TERMS_REQUIRED" -> "Aceite os Termos e a Política de Privacidade para continuar."
    "BIRTH_YEAR_LOCKED" -> "O ano de nascimento já foi confirmado e não pode ser alterado aqui."
    "INVALID_GENDER_IDENTITY", "INVALID_GENDER_SELF_DESCRIPTION", "INVALID_GENDER_VISIBILITY" ->
        "Revise as opções usadas para descrever sua identidade."
    "INVALID_DISCOVERY_PREFERENCE" ->
        "Escolha pelo menos uma opção válida para a descoberta."
    "DISCOVERY_CURSOR_STALE" ->
        "Sua preferência mudou. A descoberta foi reiniciada com o novo filtro."
    "ACCOUNT_NOT_ACTIVE", "ONBOARDING_REQUIRED" -> "Conclua as etapas de acesso antes de continuar."
    "AGE_SESSION_RATE_LIMITED" -> "Muitas tentativas foram iniciadas. Aguarde antes de tentar novamente."
    "AGE_SESSION_IN_PROGRESS" -> "A verificação já está sendo preparada. Aguarde e atualize o status."
    "AGE_PROVIDER_NOT_CONFIGURED", "BACKEND_NOT_CONFIGURED" ->
        "A verificação de maioridade ainda não está disponível."
    "AGE_PROVIDER_UNAVAILABLE", "AGE_SESSION_CREATE_FAILED" ->
        "Não foi possível abrir a verificação agora. Tente novamente em instantes."
    "AGE_PROVIDER_INVALID_RESPONSE", "AGE_PROVIDER_INVALID_URL" ->
        "A resposta segura da verificação não pôde ser validada."
    "AGE_REVIEW_PENDING" -> "Sua verificação está em análise. Atualize o status mais tarde."
    "ACCOUNT_SUSPENDED", "ACCOUNT_DELETED", "ACCOUNT_UNAVAILABLE" ->
        "Esta conta não está disponível."
    else -> "Não foi possível concluir agora. Verifique sua conexão e tente novamente."
}

private fun Throwable.toProfilePhotoMessage(): String = when (matcherCode()) {
    "INVALID_PROFILE_PHOTO_SIZE", "PROFILE_PHOTO_TOO_LARGE" ->
        "A foto precisa ter no máximo 5 MB."
    "INVALID_PROFILE_PHOTO_FORMAT", "PROFILE_PHOTO_INVALID",
    "INVALID_PROFILE_PHOTO_METADATA", "INVALID_PROFILE_PHOTO_PATH",
    "PROFILE_PHOTO_NOT_FOUND" ->
        "Escolha uma foto JPG, PNG ou WebP válida."
    "ACCOUNT_NOT_ACTIVE", "PROFILE_NOT_FOUND" ->
        "Conclua seu perfil antes de enviar uma foto."
    else -> "Não foi possível enviar a foto agora. Tente novamente."
}

private val PrivateAlbumAccessErrors = setOf(
    "PRIVATE_ALBUM_FORBIDDEN",
    "PRIVATE_ALBUM_ACCESS_DENIED",
    "ALBUM_ACCESS_DENIED",
    "PRIVATE_ALBUM_NOT_FOUND",
    "PRIVATE_ALBUM_NOT_AVAILABLE",
    "PRIVATE_ALBUM_ITEM_NOT_FOUND",
    "PRIVATE_ALBUM_OBJECT_NOT_FOUND",
    "ALBUM_BLOCKED",
    "ALBUM_ACCESS_BLOCKED",
)

private fun Throwable.toPrivateAlbumMessage(): String = when (matcherCode()) {
    "CONTENT_POLICY_REQUIRED" ->
        "Aceite a Política de Conteúdo antes de adicionar uma foto."
    "PRIVATE_ALBUM_LIMIT_REACHED" ->
        "Seu álbum já tem o limite de 10 fotos."
    "INVALID_PRIVATE_ALBUM_IMAGE_SIZE" ->
        "A foto privada precisa ter no máximo 5 MB."
    "INVALID_PRIVATE_ALBUM_IMAGE_FORMAT", "INVALID_PRIVATE_ALBUM_MEDIA_TYPE",
    "INVALID_PRIVATE_ALBUM_PATH" ->
        "Escolha uma imagem JPG, PNG ou WebP válida."
    "PRIVATE_ALBUM_FORBIDDEN", "PRIVATE_ALBUM_ACCESS_DENIED", "ALBUM_ACCESS_DENIED",
    "PRIVATE_ALBUM_NOT_FOUND", "PRIVATE_ALBUM_NOT_AVAILABLE",
    "PRIVATE_ALBUM_ITEM_NOT_FOUND", "PRIVATE_ALBUM_OBJECT_NOT_FOUND",
    "ALBUM_BLOCKED", "ALBUM_ACCESS_BLOCKED" ->
        "Este álbum privado não está mais disponível para você."
    "ACCOUNT_NOT_ACTIVE", "ACCOUNT_SUSPENDED", "ACCOUNT_DELETED" ->
        "Esta conta não pode usar álbuns privados agora."
    else -> "Não foi possível concluir a ação no álbum agora. Tente novamente."
}
