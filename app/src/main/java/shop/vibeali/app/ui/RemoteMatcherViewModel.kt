package shop.vibeali.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import shop.vibeali.app.data.remote.AgeVerificationGateway
import shop.vibeali.app.data.remote.AgeVerificationStatus
import shop.vibeali.app.data.remote.ActiveSanction
import shop.vibeali.app.data.remote.AuthGateway
import shop.vibeali.app.data.remote.CompleteOnboardingRequest
import shop.vibeali.app.data.remote.DiscoveryPage
import shop.vibeali.app.data.remote.EmailOtpRequestException
import shop.vibeali.app.data.remote.EmailOtpRequestFailure
import shop.vibeali.app.data.remote.GenderSettings
import shop.vibeali.app.data.remote.MatcherSession
import shop.vibeali.app.data.remote.PrivateAlbum
import shop.vibeali.app.data.remote.PrivateAlbumGateway
import shop.vibeali.app.data.remote.PrivateAlbumGrant
import shop.vibeali.app.data.remote.PrivateAlbumItem
import shop.vibeali.app.data.remote.PrivateAlbumReportReason
import shop.vibeali.app.data.remote.PrivacyCenter
import shop.vibeali.app.data.remote.ProfileGateway
import shop.vibeali.app.data.remote.RemoteChatGateway
import shop.vibeali.app.data.remote.RemoteProfile
import shop.vibeali.app.data.remote.SharedPrivateAlbum
import shop.vibeali.app.data.remote.UpdateGenderSettingsRequest
import shop.vibeali.app.data.remote.matcherCode
import shop.vibeali.app.data.remote.toEmailOtpRequestFailure
import shop.vibeali.app.data.push.DisabledPushGateway
import shop.vibeali.app.data.push.PushGateway
import shop.vibeali.app.domain.chat.ChatSnapshot
import shop.vibeali.app.domain.chat.ChatDeliveryStatus
import shop.vibeali.app.domain.chat.ChatMediaStatus
import shop.vibeali.app.domain.chat.ChatMessage
import shop.vibeali.app.domain.chat.ChatMessageKind
import shop.vibeali.app.domain.chat.ReportReason
import shop.vibeali.app.domain.chat.SendMessageResult
import shop.vibeali.app.domain.chat.StartConversationResult
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val EMAIL_OTP_LENGTH = 6
internal const val EMAIL_OTP_RESEND_COOLDOWN_SECONDS = 60
internal const val EMAIL_OTP_OPERATION_TIMEOUT_MILLIS = 15_000L
internal const val RESUME_REFRESH_MIN_INTERVAL_NANOS = 15_000_000_000L

enum class SignedInStage {
    Resolving,
    Onboarding,
    Active,
    Unavailable,
}

enum class OtpDeliveryStatus {
    Confirmed,
    Indeterminate,
    RateLimited,
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

data class ChatPhotoPreviewUiState(
    val messageId: String? = null,
    val bytes: ByteArray? = null,
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
    val otpDeliveryStatus: OtpDeliveryStatus? = null,
    val otpChallengeGeneration: Long = 0,
    val otpResendSecondsRemaining: Int = 0,
    val profile: RemoteProfile? = null,
    val genderSettings: GenderSettings? = null,
    val privateAlbum: PrivateAlbumUiState = PrivateAlbumUiState(),
    val discovery: DiscoveryPage = DiscoveryPage(emptyList(), null),
    val favoriteProfiles: List<RemoteProfile> = emptyList(),
    val privacyCenter: PrivacyCenter = PrivacyCenter(),
    val activeSanction: ActiveSanction? = null,
    val accountExportJson: String? = null,
    val advancedDiscoveryActive: Boolean = false,
    val chat: ChatSnapshot = ChatSnapshot(0, emptyList(), emptySet(), emptyList()),
    val chatPhotoPreview: ChatPhotoPreviewUiState = ChatPhotoPreviewUiState(),
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
    private val pushGateway: PushGateway = DisabledPushGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RemoteMatcherUiState())
    val uiState: StateFlow<RemoteMatcherUiState> = mutableState.asStateFlow()

    private val effectChannel = Channel<RemoteMatcherEffect>(Channel.BUFFERED)
    val effects: Flow<RemoteMatcherEffect> = effectChannel.receiveAsFlow()

    private var realtimeJob: Job? = null
    private var authJob: Job? = null
    private var otpCooldownJob: Job? = null
    private var ageVerificationJob: Job? = null
    private var profilePhotoJob: Job? = null
    private var discoveryPaginationJob: Job? = null
    private var privateAlbumJob: Job? = null
    private var privateAlbumSummaryJob: Job? = null
    private val typingJobs = mutableMapOf<String, Job>()
    private val typingActive = mutableSetOf<String>()
    private var albumRevalidationJob: Job? = null
    private var sessionGeneration = 0L
    private var privateAlbumGeneration = 0L
    private var lastAgeVerificationReturnSignal = 0
    private var ageVerificationRefreshPending = false
    private val authOperationInFlight = AtomicBoolean(false)
    private var authOperationGeneration = 0L
    private val otpCooldownSecondsByEmail = mutableMapOf<String, Int>()
    private val pendingChatPhotoBytes = mutableMapOf<String, ByteArray>()
    private var lastFullRefreshNanos = 0L

    init {
        viewModelScope.launch {
            authGateway.session.collectLatest { session ->
                sessionGeneration += 1
                cancelAuthOperation()
                if (session is MatcherSession.SignedIn) {
                    otpCooldownJob?.cancel()
                    otpCooldownJob = null
                    otpCooldownSecondsByEmail.clear()
                }
                invalidatePrivateAlbumWork()
                wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
                wipePendingChatPhotos()
                closeChatPhoto()
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
                        otpDeliveryStatus = if (session is MatcherSession.SignedOut) {
                            current.otpDeliveryStatus
                        } else {
                            null
                        },
                        otpChallengeGeneration = if (session is MatcherSession.SignedOut) {
                            current.otpChallengeGeneration
                        } else {
                            0
                        },
                        otpResendSecondsRemaining = if (session is MatcherSession.SignedOut) {
                            current.otpResendSecondsRemaining
                        } else {
                            0
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
        val cooldownSeconds = otpCooldownSecondsByEmail[normalized.otpCooldownKey()] ?: 0
        if (cooldownSeconds > 0) {
            setError("Aguarde ${cooldownSeconds}s para reenviar o código.")
            return
        }
        launchAuthRemote(
            errorMessage = { it.toOtpRequestMessage() },
            onFailure = { error ->
                val failure = error.toEmailOtpRequestFailure()
                if (
                    failure == EmailOtpRequestFailure.RateLimited ||
                    failure == EmailOtpRequestFailure.DeliveryUnknown
                ) {
                    mutableState.update { current ->
                        val shouldResetInput =
                            failure == EmailOtpRequestFailure.DeliveryUnknown ||
                                current.otpRequestedFor != normalized
                        current.copy(
                            otpRequestedFor = normalized,
                            otpDeliveryStatus = when (failure) {
                                EmailOtpRequestFailure.RateLimited -> OtpDeliveryStatus.RateLimited
                                else -> OtpDeliveryStatus.Indeterminate
                            },
                            otpChallengeGeneration = if (shouldResetInput) {
                                current.otpChallengeGeneration + 1
                            } else {
                                current.otpChallengeGeneration
                            },
                        )
                    }
                    val retryAfterSeconds = (error as? EmailOtpRequestException)
                        ?.retryAfterSeconds
                        ?: 0
                    startOtpResendCooldown(
                        normalized,
                        maxOf(EMAIL_OTP_RESEND_COOLDOWN_SECONDS, retryAfterSeconds),
                    )
                }
            },
        ) { token ->
            authGateway.requestEmailOtp(normalized)
            ensureSessionWorkIsCurrent(token)
            mutableState.update {
                it.copy(
                    otpRequestedFor = normalized,
                    otpDeliveryStatus = OtpDeliveryStatus.Confirmed,
                    otpChallengeGeneration = it.otpChallengeGeneration + 1,
                )
            }
            startOtpResendCooldown(normalized)
        }
    }

    fun verifyOtp(email: String, token: String) {
        val normalizedToken = token.trim()
        if (normalizedToken.length != EMAIL_OTP_LENGTH || normalizedToken.any { !it.isDigit() }) {
            setError("Digite o código de 6 dígitos.")
            return
        }
        launchAuthRemote(errorMessage = { it.toOtpVerificationMessage() }) { token ->
            authGateway.verifyEmailOtp(email, normalizedToken)
            ensureSessionWorkIsCurrent(token)
        }
    }

    fun changeOtpEmail() {
        if (authOperationInFlight.get()) return
        mutableState.update {
            it.copy(
                otpRequestedFor = null,
                otpDeliveryStatus = null,
                otpResendSecondsRemaining = 0,
                errorMessage = null,
            )
        }
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
        val displayedAlbum = state.privateAlbum.myAlbum
        val scheduled = runPrivateAlbumOperation { token ->
            try {
                val targetAlbum = displayedAlbum ?: privateAlbumGateway.createPrivateAlbum(
                    contentPolicyVersion = PrivateAlbumContentPolicyVersion,
                    contentPolicyAccepted = true,
                )
                ensurePrivateAlbumWorkIsCurrent(token)
                val finalizedItem = privateAlbumGateway.uploadPrivateAlbumImage(
                    targetAlbum.albumId,
                    jpegBytes,
                )
                ensurePrivateAlbumWorkIsCurrent(token)
                preserveFinalizedPrivateAlbumItem(token, targetAlbum, finalizedItem)
                try {
                    loadMyPrivateAlbum(includeBytes = true, token = token)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error.matcherCode() in PrivateAlbumStateInvalidatingErrors) throw error
                    if (isPrivateAlbumWorkCurrent(token)) {
                        setError(
                            "Foto adicionada. Não foi possível carregar a prévia agora; " +
                                "abra o álbum novamente para tentar.",
                        )
                    }
                }
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

    fun revokePrivateAlbumGrants(recipientIds: Set<String>) {
        if (!requireActiveAccount() || recipientIds.isEmpty()) return
        val expectedAlbumId = mutableState.value.privateAlbum.myAlbum?.albumId ?: return
        runPrivateAlbumOperation { token ->
            try {
                recipientIds.sorted().forEach { recipientId ->
                    privateAlbumGateway.revokePrivateAlbumAccess(expectedAlbumId, recipientId)
                    ensurePrivateAlbumWorkIsCurrent(token)
                }
            } finally {
                if (isPrivateAlbumWorkCurrent(token)) {
                    loadPrivateAlbumSummaries(token)
                }
            }
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

    fun reportPrivateAlbum(details: String, itemId: String? = null) {
        val received = mutableState.value.privateAlbum.destination as? PrivateAlbumDestination.Received
            ?: return
        val sessionToken = currentSessionWorkToken()
        runPrivateAlbumOperation { token ->
            privateAlbumGateway.reportPrivateAlbum(
                albumId = received.albumId,
                reason = PrivateAlbumReportReason.InappropriatePhoto,
                details = details,
                itemId = itemId,
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

    fun sendMessage(conversationId: String, body: String, replyToMessageId: String? = null): Boolean {
        if (body.isBlank() || !requireActiveAccount()) return false
        val clientMessageId = UUID.randomUUID().toString()
        setConversationTyping(conversationId, false)
        addPendingChatMessage(
            ChatMessage(
                id = "local-$clientMessageId",
                conversationId = conversationId,
                senderId = currentUserIdOrNull() ?: return false,
                body = body.trim(),
                deliveryStatus = ChatDeliveryStatus.Sending,
                clientMessageId = clientMessageId,
                replyToMessageId = replyToMessageId,
                replyPreview = mutableState.value.chat.conversations
                    .firstOrNull { it.id == conversationId }
                    ?.messages?.firstOrNull { it.id == replyToMessageId }
                    ?.let { if (it.kind == ChatMessageKind.Photo) "Foto" else it.body.take(120) },
            ),
        )
        launchRemote { token ->
            when (chatGateway.sendMessageWithReplyKey(conversationId, body, clientMessageId, replyToMessageId)) {
                is SendMessageResult.Sent -> {
                    ensureSessionWorkIsCurrent(token)
                    reloadChat(token)
                }
                SendMessageResult.InvalidMessage -> {
                    ensureSessionWorkIsCurrent(token)
                    markPendingChatMessageFailed(clientMessageId)
                    setError("Escreva uma mensagem válida antes de enviar.")
                }
                SendMessageResult.RateLimited -> {
                    ensureSessionWorkIsCurrent(token)
                    markPendingChatMessageFailed(clientMessageId)
                    setError("Você enviou mensagens muito rápido. Aguarde um pouco e tente novamente.")
                }
                SendMessageResult.NotAllowed,
                SendMessageResult.NotFound -> {
                    ensureSessionWorkIsCurrent(token)
                    markPendingChatMessageFailed(clientMessageId)
                    setError("Esta conversa não está mais disponível.")
                }
            }
        }
        return true
    }

    fun setConversationTyping(conversationId: String, typing: Boolean) {
        if (!requireActiveAccount()) return
        typingJobs.remove(conversationId)?.cancel()
        if (!typing) {
            if (!typingActive.remove(conversationId)) return
            launchRemote { chatGateway.setConversationTyping(conversationId, false) }
            return
        }
        if (typingActive.add(conversationId)) {
            launchRemote { chatGateway.setConversationTyping(conversationId, true) }
        }
        typingJobs[conversationId] = viewModelScope.launch {
            delay(4_000)
            typingActive.remove(conversationId)
            runCatching { chatGateway.setConversationTyping(conversationId, false) }
            typingJobs.remove(conversationId)
        }
    }

    fun toggleMessageReaction(messageId: String) {
        if (!requireActiveAccount() || messageId.startsWith("local-")) return
        launchRemote { token ->
            chatGateway.toggleMessageReaction(messageId)
            ensureSessionWorkIsCurrent(token)
            reloadChat(token)
        }
    }

    fun sendPhoto(conversationId: String, jpegBytes: ByteArray): Boolean {
        if (jpegBytes.isEmpty() || !requireActiveAccount()) return false
        val clientMessageId = UUID.randomUUID().toString()
        val senderId = currentUserIdOrNull() ?: return false
        pendingChatPhotoBytes[clientMessageId] = jpegBytes.copyOf()
        addPendingChatMessage(
            ChatMessage(
                id = "local-$clientMessageId",
                conversationId = conversationId,
                senderId = senderId,
                kind = ChatMessageKind.Photo,
                mediaStatus = ChatMediaStatus.Pending,
                deliveryStatus = ChatDeliveryStatus.Sending,
                clientMessageId = clientMessageId,
            ),
        )
        sendPendingPhoto(conversationId, clientMessageId)
        return true
    }

    fun retryMessage(message: ChatMessage) {
        val clientMessageId = message.clientMessageId ?: return
        if (message.deliveryStatus != ChatDeliveryStatus.Failed || !requireActiveAccount()) return
        markPendingChatMessageSending(clientMessageId)
        if (message.kind == ChatMessageKind.Photo) {
            sendPendingPhoto(message.conversationId, clientMessageId)
        } else {
            launchRemote { token ->
                when (chatGateway.sendMessageWithKey(message.conversationId, message.body, clientMessageId)) {
                    is SendMessageResult.Sent -> reloadChat(token)
                    SendMessageResult.RateLimited -> {
                        markPendingChatMessageFailed(clientMessageId)
                        setError("Aguarde um pouco antes de tentar novamente.")
                    }
                    else -> {
                        ensureSessionWorkIsCurrent(token)
                        markPendingChatMessageFailed(clientMessageId)
                    }
                }
            }
        }
    }

    fun markConversationRead(conversationId: String) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            chatGateway.markConversationRead(conversationId)
            reloadChat(token)
        }
    }

    fun setConversationMuted(conversationId: String, muted: Boolean) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            chatGateway.setConversationMuted(conversationId, muted)
            reloadChat(token)
        }
    }

    fun setConversationArchived(conversationId: String, archived: Boolean, onChanged: () -> Unit = {}) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            chatGateway.setConversationArchived(conversationId, archived)
            reloadChat(token)
            onChanged()
        }
    }

    fun deleteConversation(conversationId: String, onDeleted: () -> Unit = {}) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            chatGateway.setConversationDeleted(conversationId, true)
            reloadChat(token)
            onDeleted()
        }
    }

    fun openChatPhoto(messageId: String) {
        if (!requireActiveAccount()) return
        closeChatPhoto()
        mutableState.update {
            it.copy(chatPhotoPreview = ChatPhotoPreviewUiState(messageId = messageId, loading = true))
        }
        val token = currentSessionWorkToken()
        viewModelScope.launch {
            try {
                val bytes = chatGateway.downloadChatPhoto(messageId)
                ensureSessionWorkIsCurrent(token)
                if (mutableState.value.chatPhotoPreview.messageId != messageId) {
                    bytes.fill(0)
                    return@launch
                }
                mutableState.update {
                    it.copy(chatPhotoPreview = ChatPhotoPreviewUiState(messageId = messageId, bytes = bytes))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token) && mutableState.value.chatPhotoPreview.messageId == messageId) {
                    closeChatPhoto()
                    setError("Esta foto não está mais disponível.")
                }
            }
        }
    }

    fun closeChatPhoto() {
        mutableState.value.chatPhotoPreview.bytes?.fill(0)
        mutableState.update { it.copy(chatPhotoPreview = ChatPhotoPreviewUiState()) }
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

    fun setProfileFavorite(targetUserId: String, favorite: Boolean) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            profileGateway.setProfileFavorite(targetUserId, favorite)
            ensureSessionWorkIsCurrent(token)
            val discovery = profileGateway.discoveryPage()
            ensureSessionWorkIsCurrent(token)
            val favorites = profileGateway.favoriteProfiles()
            ensureSessionWorkIsCurrent(token)
            mutableState.update {
                it.copy(discovery = discovery, favoriteProfiles = favorites, errorMessage = null)
            }
        }
    }

    fun searchDiscovery(query: String, minimumAge: Int, maximumAge: Int, verifiedOnly: Boolean, hasPhotoOnly: Boolean) {
        if (!requireActiveAccount()) return
        if (minimumAge !in 18..99 || maximumAge !in minimumAge..99) {
            setError("Escolha uma faixa de idade válida.")
            return
        }
        launchRemote { token ->
            val profiles = profileGateway.searchProfiles(query, minimumAge, maximumAge, verifiedOnly, hasPhotoOnly)
            ensureSessionWorkIsCurrent(token)
            mutableState.update {
                it.copy(discovery = DiscoveryPage(profiles, null), advancedDiscoveryActive = true, errorMessage = null)
            }
        }
    }

    fun clearAdvancedDiscovery() {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            val discovery = profileGateway.discoveryPage()
            ensureSessionWorkIsCurrent(token)
            mutableState.update { it.copy(discovery = discovery, advancedDiscoveryActive = false, errorMessage = null) }
        }
    }

    fun hideProfile(targetUserId: String, onHidden: () -> Unit) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            if (!profileGateway.hideProfile(targetUserId)) {
                setError("Não foi possível ocultar este perfil.")
                return@launchRemote
            }
            ensureSessionWorkIsCurrent(token)
            val discovery = profileGateway.discoveryPage()
            ensureSessionWorkIsCurrent(token)
            val favorites = profileGateway.favoriteProfiles()
            ensureSessionWorkIsCurrent(token)
            val privacy = profileGateway.privacyCenter()
            ensureSessionWorkIsCurrent(token)
            mutableState.update {
                it.copy(discovery = discovery, favoriteProfiles = favorites, privacyCenter = privacy, errorMessage = null)
            }
            onHidden()
        }
    }

    fun setActivityVisibility(visible: Boolean) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            profileGateway.setActivityVisibility(visible)
            ensureSessionWorkIsCurrent(token)
            mutableState.update { it.copy(privacyCenter = profileGateway.privacyCenter(), errorMessage = null) }
        }
    }

    fun unhideProfile(targetUserId: String) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            profileGateway.unhideProfile(targetUserId)
            ensureSessionWorkIsCurrent(token)
            refreshSignedInData(token)
        }
    }

    fun unblockUser(targetUserId: String) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            profileGateway.unblockUser(targetUserId)
            ensureSessionWorkIsCurrent(token)
            refreshSignedInData(token)
        }
    }

    fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
        messageId: String? = null,
        onReported: () -> Unit,
    ) {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            if (messageId != null && conversationId != null) {
                chatGateway.reportMessage(targetUserId, reason, details, conversationId, messageId)
            } else {
                chatGateway.reportUser(targetUserId, reason, details, conversationId)
            }
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
        wipePendingChatPhotos()
        closeChatPhoto()
        mutableState.update { it.copy(privateAlbum = PrivateAlbumUiState()) }
        launchRemote {
            runCatching { pushGateway.unregister() }
            authGateway.signOut()
        }
    }

    fun deleteAccount() {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            check(profileGateway.requestAccountDeletion()) { "ACCOUNT_DELETION_FAILED" }
            ensureSessionWorkIsCurrent(token)
            invalidatePrivateAlbumWork()
            wipePrivateAlbumBytes(mutableState.value.privateAlbum.visibleBytes)
            wipePendingChatPhotos()
            closeChatPhoto()
            runCatching { pushGateway.unregister() }
            authGateway.signOut()
        }
    }

    fun updateMyProfile(displayName: String, bio: String, intent: String) {
        if (!requireActiveAccount()) return
        if (displayName.trim().length !in 2..40 || bio.trim().length > 500 || intent.trim().length !in 1..80) {
            setError("Revise o nome, a bio e o que você procura.")
            return
        }
        launchRemote { token ->
            profileGateway.updateMyProfile(displayName, bio, intent)
            ensureSessionWorkIsCurrent(token)
            refreshSignedInData(token)
        }
    }

    fun updateMyInterests(interests: Set<String>) {
        if (!requireActiveAccount()) return
        if (interests.size > 8 || interests.any { it !in PROFILE_INTEREST_IDS }) {
            setError("Escolha no máximo oito interesses disponíveis.")
            return
        }
        launchRemote { token ->
            profileGateway.updateMyInterests(interests.sorted())
            ensureSessionWorkIsCurrent(token)
            refreshSignedInData(token)
        }
    }

    fun prepareAccountExport() {
        if (!requireActiveAccount()) return
        launchRemote { token ->
            val exported = profileGateway.exportMyAccountData()
            ensureSessionWorkIsCurrent(token)
            mutableState.update { it.copy(accountExportJson = exported, errorMessage = null) }
        }
    }

    fun accountExportHandled() {
        mutableState.update { it.copy(accountExportJson = null) }
    }

    fun submitModerationAppeal(statement: String) {
        val sanction = mutableState.value.activeSanction ?: return
        if (statement.trim().length !in 20..2000) {
            setError("Explique o pedido de revisão com pelo menos 20 caracteres.")
            return
        }
        launchRemote { token ->
            profileGateway.submitModerationAppeal(sanction.sanctionId, statement)
            ensureSessionWorkIsCurrent(token)
            mutableState.update {
                it.copy(activeSanction = profileGateway.activeSanction(), errorMessage = "Recurso enviado para análise.")
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    fun refreshOnResume() {
        if (!requireActiveAccount()) return
        val elapsedNanos = System.nanoTime() - lastFullRefreshNanos
        if (elapsedNanos in 0 until RESUME_REFRESH_MIN_INTERVAL_NANOS) return
        launchRemote { token -> refreshSignedInData(token) }
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
                    val sanction = if (access.accountStatus == "suspended") profileGateway.activeSanction() else null
                    ensureSessionWorkIsCurrent(token)
                    mutableState.update {
                        it.copy(
                            signedInStage = SignedInStage.Unavailable,
                            errorMessage = "Esta conta não está disponível.",
                            activeSanction = sanction,
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
        val loaded = coroutineScope {
            val genderSettings = async { profileGateway.getGenderSettings() }
            val discovery = async { profileGateway.discoveryPage() }
            val favoriteProfiles = async { profileGateway.favoriteProfiles() }
            val privacyCenter = async { profileGateway.privacyCenter() }
            val chat = async { chatGateway.snapshot() }
            ActiveAccountPayload(
                genderSettings = genderSettings.await(),
                discovery = discovery.await(),
                favoriteProfiles = favoriteProfiles.await(),
                privacyCenter = privacyCenter.await(),
                chat = chat.await(),
            )
        }
        ensureSessionWorkIsCurrent(token)
        realtimeJob?.cancel()
        realtimeJob = null
        mutableState.update {
            it.copy(
                signedInStage = SignedInStage.Active,
                profile = profile,
                genderSettings = loaded.genderSettings,
                discovery = loaded.discovery,
                favoriteProfiles = loaded.favoriteProfiles,
                privacyCenter = loaded.privacyCenter,
                chat = loaded.chat,
                ageVerificationOpen = if (
                    it.ageVerificationStatus == AgeVerificationStatus.Verified
                ) false else it.ageVerificationOpen,
                ageVerificationConsentGranted = if (
                    it.ageVerificationStatus == AgeVerificationStatus.Verified
                ) false else it.ageVerificationConsentGranted,
            )
        }
        lastFullRefreshNanos = System.nanoTime()
        runCatching { pushGateway.register() }
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

    private data class ActiveAccountPayload(
        val genderSettings: GenderSettings,
        val discovery: DiscoveryPage,
        val favoriteProfiles: List<RemoteProfile>,
        val privacyCenter: PrivacyCenter,
        val chat: ChatSnapshot,
    )

    private suspend fun reloadChat(token: SessionWorkToken = currentSessionWorkToken()) {
        if (mutableState.value.signedInStage != SignedInStage.Active) return
        val chat = chatGateway.snapshot()
        ensureSessionWorkIsCurrent(token)
        mutableState.update { current ->
            if (isSessionWorkCurrent(token)) current.copy(chat = chat) else current
        }
    }

    private fun sendPendingPhoto(conversationId: String, clientMessageId: String) {
        val bytes = pendingChatPhotoBytes[clientMessageId] ?: run {
            markPendingChatMessageFailed(clientMessageId)
            return
        }
        launchRemote { token ->
            when (chatGateway.sendPhoto(conversationId, bytes, clientMessageId)) {
                is SendMessageResult.Sent -> {
                    pendingChatPhotoBytes.remove(clientMessageId)?.fill(0)
                    reloadChat(token)
                }
                else -> {
                    ensureSessionWorkIsCurrent(token)
                    markPendingChatMessageFailed(clientMessageId)
                    setError("Não foi possível enviar a foto. Toque nela para tentar novamente.")
                }
            }
        }
    }

    private fun addPendingChatMessage(message: ChatMessage) {
        mutableState.update { state ->
            state.copy(
                chat = state.chat.copy(
                    conversations = state.chat.conversations.map { conversation ->
                        if (conversation.id == message.conversationId) {
                            conversation.copy(messages = conversation.messages + message)
                        } else conversation
                    },
                ),
            )
        }
    }

    private fun markPendingChatMessageSending(clientMessageId: String) =
        updatePendingChatMessage(clientMessageId) { it.copy(deliveryStatus = ChatDeliveryStatus.Sending) }

    private fun markPendingChatMessageFailed(clientMessageId: String) =
        updatePendingChatMessage(clientMessageId) { it.copy(deliveryStatus = ChatDeliveryStatus.Failed) }

    private fun updatePendingChatMessage(clientMessageId: String, transform: (ChatMessage) -> ChatMessage) {
        mutableState.update { state ->
            state.copy(
                chat = state.chat.copy(
                    conversations = state.chat.conversations.map { conversation ->
                        conversation.copy(
                            messages = conversation.messages.map { message ->
                                if (message.clientMessageId == clientMessageId) transform(message) else message
                            },
                        )
                    },
                ),
            )
        }
    }

    private fun wipePendingChatPhotos() {
        pendingChatPhotoBytes.values.forEach { it.fill(0) }
        pendingChatPhotoBytes.clear()
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
            replaceVisiblePrivateAlbumBytes(
                token = token,
                destination = PrivateAlbumDestination.Mine,
                items = albumState.myItems,
                bytes = emptyMap(),
            )
            ensurePrivateAlbumWorkIsCurrent(token)
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
                    if (error.matcherCode() in PrivateAlbumStateInvalidatingErrors) clearVisiblePrivateAlbum()
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

    private fun preserveFinalizedPrivateAlbumItem(
        token: PrivateAlbumWorkToken,
        targetAlbum: PrivateAlbum,
        finalizedItem: PrivateAlbumItem,
    ) {
        updatePrivateAlbumState(token) { current ->
            val albumState = current.privateAlbum
            val currentAlbum = albumState.myAlbum
            if (albumState.destination != PrivateAlbumDestination.Mine ||
                currentAlbum?.albumId?.let { it != targetAlbum.albumId } == true
            ) {
                return@updatePrivateAlbumState current
            }
            val alreadyKnown = albumState.myItems.any { it.itemId == finalizedItem.itemId }
            val mergedItems = (albumState.myItems.filterNot { it.itemId == finalizedItem.itemId } +
                finalizedItem).sortedBy { it.position }
            val albumSnapshot = currentAlbum ?: targetAlbum
            val preservedAlbum = albumSnapshot.copy(
                itemCount = maxOf(
                    albumSnapshot.itemCount + if (alreadyKnown) 0 else 1,
                    mergedItems.size,
                ),
            )
            current.copy(
                privateAlbum = albumState.copy(
                    myAlbum = preservedAlbum,
                    myItems = mergedItems,
                ),
            )
        }
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

    private fun currentUserIdOrNull(): String? =
        (mutableState.value.session as? MatcherSession.SignedIn)?.userId

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

    private fun launchAuthRemote(
        errorMessage: (Throwable) -> String,
        onFailure: (Throwable) -> Unit = {},
        block: suspend (SessionWorkToken) -> Unit,
    ) {
        if (!authOperationInFlight.compareAndSet(false, true)) return
        val operationGeneration = ++authOperationGeneration
        val token = currentSessionWorkToken()
        mutableState.update { it.copy(loading = true, errorMessage = null) }
        val operationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!isSessionWorkCurrent(token)) return@launch
                withTimeout(EMAIL_OTP_OPERATION_TIMEOUT_MILLIS) {
                    block(token)
                }
            } catch (error: TimeoutCancellationException) {
                if (isSessionWorkCurrent(token)) {
                    val timeout = EmailOtpRequestException(
                        failure = EmailOtpRequestFailure.DeliveryUnknown,
                        cause = error,
                    )
                    onFailure(timeout)
                    setError(errorMessage(timeout))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isSessionWorkCurrent(token)) {
                    onFailure(error)
                    setError(errorMessage(error))
                }
            } finally {
                if (authOperationGeneration == operationGeneration) {
                    authJob = null
                    authOperationInFlight.set(false)
                    if (isSessionWorkCurrent(token)) {
                        mutableState.update { it.copy(loading = false) }
                    }
                }
            }
        }
        authJob = operationJob
        operationJob.start()
    }

    private fun cancelAuthOperation() {
        authOperationGeneration += 1
        authJob?.cancel()
        authJob = null
        authOperationInFlight.set(false)
    }

    private fun startOtpResendCooldown(email: String, minimumSeconds: Int = EMAIL_OTP_RESEND_COOLDOWN_SECONDS) {
        val cooldownKey = email.otpCooldownKey()
        otpCooldownSecondsByEmail[cooldownKey] = maxOf(
            otpCooldownSecondsByEmail[cooldownKey] ?: 0,
            minimumSeconds,
        )
        updateVisibleOtpCooldown()
        if (otpCooldownJob?.isActive == true) return
        otpCooldownJob = viewModelScope.launch {
            while (otpCooldownSecondsByEmail.isNotEmpty()) {
                delay(1_000)
                otpCooldownSecondsByEmail.replaceAll { _, remaining ->
                    (remaining - 1).coerceAtLeast(0)
                }
                otpCooldownSecondsByEmail.entries.removeAll { it.value == 0 }
                updateVisibleOtpCooldown()
            }
            otpCooldownJob = null
        }
    }

    private fun updateVisibleOtpCooldown() {
        mutableState.update { current ->
            current.copy(
                otpResendSecondsRemaining = current.otpRequestedFor
                    ?.let { otpCooldownSecondsByEmail[it.otpCooldownKey()] }
                    ?: 0,
            )
        }
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
        private val pushGateway: PushGateway = DisabledPushGateway,
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
                pushGateway,
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

private val PROFILE_INTEREST_IDS = setOf(
    "amizade", "conversa", "cinema", "música", "viagens", "games",
    "academia", "gastronomia", "pets", "natureza", "arte", "tecnologia",
)

private fun String.otpCooldownKey(): String = trim().lowercase(Locale.ROOT)

internal fun isTrustedAgeVerificationUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("verify.didit.me", ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)

internal const val AGE_VERIFICATION_CONSENT_REQUIRED_MESSAGE =
    "Autorize o processamento pela Didit antes de iniciar a verificação."

private fun Throwable.toOtpRequestMessage(): String = when (
    (this as? EmailOtpRequestException)?.failure ?: toEmailOtpRequestFailure()
) {
    EmailOtpRequestFailure.RateLimited ->
        "Muitos códigos foram solicitados. Aguarde antes de tentar novamente."
    EmailOtpRequestFailure.DeliveryUnknown ->
        "A resposta demorou, mas o código pode ter sido enviado. Verifique seu e-mail e digite-o se chegar."
    EmailOtpRequestFailure.NetworkUnavailable ->
        "Não foi possível conectar ao serviço de login. Confira a internet e tente novamente."
    EmailOtpRequestFailure.ProviderRejected ->
        "O serviço de e-mail não conseguiu enviar o código. Tente novamente em instantes."
}

private fun Throwable.toOtpVerificationMessage(): String = when (toEmailOtpRequestFailure()) {
    EmailOtpRequestFailure.RateLimited ->
        "Muitas verificações foram feitas. Aguarde antes de tentar novamente."
    EmailOtpRequestFailure.DeliveryUnknown ->
        "A validação demorou mais que o esperado. Confira a conexão e digite o código novamente."
    EmailOtpRequestFailure.NetworkUnavailable ->
        "Não foi possível conectar ao serviço de login. Confira a internet e tente novamente."
    EmailOtpRequestFailure.ProviderRejected ->
        "Código inválido ou expirado. Solicite um novo código."
}

private fun Throwable.toUserMessage(): String = when (matcherCode()) {
    "ADULTS_ONLY" -> "O VibeAli é exclusivo para pessoas com 18 anos ou mais."
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

private val PrivateAlbumStateInvalidatingErrors = PrivateAlbumAccessErrors + setOf(
    "AUTH_REQUIRED",
    "ACCOUNT_NOT_ACTIVE",
    "ACCOUNT_SUSPENDED",
    "ACCOUNT_DELETED",
    "ACCOUNT_UNAVAILABLE",
    "PRIVATE_ALBUM_CHANGED",
    "INVALID_PRIVATE_ALBUM_RESPONSE",
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
    "PRIVATE_ALBUM_STORAGE_ACCESS_DENIED" ->
        "Não foi possível enviar a foto privada agora. Tente novamente; " +
            "se continuar, saia e entre na conta."
    "PRIVATE_ALBUM_FORBIDDEN", "PRIVATE_ALBUM_ACCESS_DENIED", "ALBUM_ACCESS_DENIED",
    "PRIVATE_ALBUM_NOT_FOUND", "PRIVATE_ALBUM_NOT_AVAILABLE",
    "PRIVATE_ALBUM_ITEM_NOT_FOUND", "PRIVATE_ALBUM_OBJECT_NOT_FOUND",
    "ALBUM_BLOCKED", "ALBUM_ACCESS_BLOCKED" ->
        "Este álbum privado não está mais disponível para você."
    "ACCOUNT_NOT_ACTIVE", "ACCOUNT_SUSPENDED", "ACCOUNT_DELETED" ->
        "Esta conta não pode usar álbuns privados agora."
    else -> "Não foi possível concluir a ação no álbum agora. Tente novamente."
}
