package com.matcher.app.ui

import com.matcher.app.data.remote.AgeVerificationGateway
import com.matcher.app.data.remote.AgeVerificationSession
import com.matcher.app.data.remote.AgeVerificationSnapshot
import com.matcher.app.data.remote.AgeVerificationStatus
import com.matcher.app.data.remote.AuthGateway
import com.matcher.app.data.remote.CompleteOnboardingRequest
import com.matcher.app.data.remote.CompleteOnboardingResponse
import com.matcher.app.data.remote.DiscoveryPage
import com.matcher.app.data.remote.EmailOtpRequestException
import com.matcher.app.data.remote.EmailOtpRequestFailure
import com.matcher.app.data.remote.MatcherSession
import com.matcher.app.data.remote.PrivateAlbum
import com.matcher.app.data.remote.PrivateAlbumContent
import com.matcher.app.data.remote.PrivateAlbumGateway
import com.matcher.app.data.remote.PrivateAlbumGrant
import com.matcher.app.data.remote.PrivateAlbumItem
import com.matcher.app.data.remote.PrivateAlbumReportReason
import com.matcher.app.data.remote.ProfileGateway
import com.matcher.app.data.remote.RemoteChatGateway
import com.matcher.app.data.remote.RemoteProfile
import com.matcher.app.data.remote.SharedPrivateAlbum
import com.matcher.app.data.remote.GenderSettings
import com.matcher.app.data.remote.UpdateGenderSettingsRequest
import com.matcher.app.domain.chat.ChatSnapshot
import com.matcher.app.domain.chat.ModerationCase
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.domain.chat.SendMessageResult
import com.matcher.app.domain.chat.StartConversationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMatcherViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun otpRequestNormalizesEmailAndKeepsOnlyTheDeliveryState() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("  pessoa@matcher.invalid ")
        advanceUntilIdle()

        assertEquals("pessoa@matcher.invalid", auth.requestedEmail)
        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        assertEquals(OtpDeliveryStatus.Confirmed, viewModel.uiState.value.otpDeliveryStatus)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun rapidOtpRequestsStartOnlyOneProviderCall() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply { requestGate = CompletableDeferred() }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        viewModel.requestOtp("pessoa@matcher.invalid")
        assertTrue(viewModel.uiState.value.loading)
        runCurrent()

        assertEquals(1, auth.requestCalls)
        auth.requestGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
    }

    @Test
    fun resendIsBlockedDuringTheLocalCooldown() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        assertEquals(EMAIL_OTP_RESEND_COOLDOWN_SECONDS, viewModel.uiState.value.otpResendSecondsRemaining)

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        assertEquals(1, auth.requestCalls)

        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.otpResendSecondsRemaining)
    }

    @Test
    fun changingThenReenteringTheSameEmailDoesNotBypassCooldown() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        viewModel.changeOtpEmail()
        viewModel.requestOtp("PESSOA@MATCHER.INVALID")
        runCurrent()

        assertEquals(1, auth.requestCalls)
        assertEquals(null, viewModel.uiState.value.otpRequestedFor)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().startsWith("Aguarde "))
        advanceTimeBy(EMAIL_OTP_RESEND_COOLDOWN_SECONDS * 1_000L)
        runCurrent()

        viewModel.requestOtp("PESSOA@MATCHER.INVALID")
        runCurrent()
        assertEquals(2, auth.requestCalls)
        advanceUntilIdle()
    }

    @Test
    fun confirmedResendCreatesANewOtpInputGeneration() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        val firstGeneration = viewModel.uiState.value.otpChallengeGeneration
        advanceTimeBy(EMAIL_OTP_RESEND_COOLDOWN_SECONDS * 1_000L)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals(2, auth.requestCalls)
        assertEquals(firstGeneration + 1, viewModel.uiState.value.otpChallengeGeneration)
        advanceUntilIdle()
    }

    @Test
    fun stalledOtpRequestBecomesIndeterminateAtTheClientTimeout() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply { requestGate = CompletableDeferred() }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        advanceTimeBy(EMAIL_OTP_OPERATION_TIMEOUT_MILLIS)
        runCurrent()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals(OtpDeliveryStatus.Indeterminate, viewModel.uiState.value.otpDeliveryStatus)
        assertEquals(1, auth.requestCalls)
        assertEquals(
            "A resposta demorou, mas o código pode ter sido enviado. Verifique seu e-mail e digite-o se chegar.",
            viewModel.uiState.value.errorMessage,
        )
        advanceUntilIdle()
    }

    @Test
    fun sessionChangeCancelsOldOtpWorkAndAllowsANewRequest() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply { requestGate = CompletableDeferred() }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        assertEquals(1, auth.requestCalls)

        auth.session.value = MatcherSession.Loading
        runCurrent()
        auth.session.value = MatcherSession.SignedOut
        auth.requestGate = null
        runCurrent()
        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals(2, auth.requestCalls)
        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        advanceUntilIdle()
    }

    @Test
    fun timeoutKeepsOtpInputAvailableWithoutAutomaticRetry() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply {
            requestFailure = EmailOtpRequestException(EmailOtpRequestFailure.DeliveryUnknown)
        }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals(1, auth.requestCalls)
        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        assertEquals(OtpDeliveryStatus.Indeterminate, viewModel.uiState.value.otpDeliveryStatus)
        assertEquals(EMAIL_OTP_RESEND_COOLDOWN_SECONDS, viewModel.uiState.value.otpResendSecondsRemaining)
        assertEquals(
            "A resposta demorou, mas o código pode ter sido enviado. Verifique seu e-mail e digite-o se chegar.",
            viewModel.uiState.value.errorMessage,
        )

        advanceUntilIdle()
        assertEquals(1, auth.requestCalls)
    }

    @Test
    fun rateLimitKeepsTheExistingOtpChallengeAvailable() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply {
            requestFailure = EmailOtpRequestException(EmailOtpRequestFailure.RateLimited)
        }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        assertEquals(OtpDeliveryStatus.RateLimited, viewModel.uiState.value.otpDeliveryStatus)
        assertEquals(EMAIL_OTP_RESEND_COOLDOWN_SECONDS, viewModel.uiState.value.otpResendSecondsRemaining)
        assertEquals(
            "Muitos códigos foram solicitados. Aguarde antes de tentar novamente.",
            viewModel.uiState.value.errorMessage,
        )

        advanceUntilIdle()
        assertEquals(1, auth.requestCalls)
    }

    @Test
    fun providerRetryAfterExtendsTheLocalCooldown() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply {
            requestFailure = EmailOtpRequestException(
                failure = EmailOtpRequestFailure.RateLimited,
                retryAfterSeconds = 90,
            )
        }
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals(90, viewModel.uiState.value.otpResendSecondsRemaining)
        advanceUntilIdle()
    }

    @Test
    fun verifyAndResendCannotOverlap() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()
        viewModel.requestOtp("pessoa@matcher.invalid")
        advanceUntilIdle()
        auth.verifyGate = CompletableDeferred()

        viewModel.verifyOtp("pessoa@matcher.invalid", "123456")
        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()

        assertEquals(1, auth.verifyCalls)
        assertEquals(1, auth.requestCalls)
        auth.verifyGate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun changeEmailIsIgnoredWhileOtpOperationIsActive() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()
        viewModel.requestOtp("pessoa@matcher.invalid")
        advanceUntilIdle()
        auth.verifyGate = CompletableDeferred()

        viewModel.verifyOtp("pessoa@matcher.invalid", "123456")
        viewModel.changeOtpEmail()

        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        auth.verifyGate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun validOtpIsDelegatedWithoutBeingStoredInUiState() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.verifyOtp("pessoa@matcher.invalid", "123456")
        advanceUntilIdle()

        assertEquals("pessoa@matcher.invalid", auth.verifiedEmail)
        assertEquals("123456", auth.verifiedOtp)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.otpRequestedFor)
    }

    @Test
    fun signedInSessionClearsOtpChallengeAndCooldown() = runTest(dispatcher) {
        val auth = FakeAuthGateway().apply {
            signedInAfterVerify = MatcherSession.SignedIn("user-test")
        }
        val viewModel = viewModel(auth = auth)
        runCurrent()
        viewModel.requestOtp("pessoa@matcher.invalid")
        runCurrent()
        assertEquals(OtpDeliveryStatus.Confirmed, viewModel.uiState.value.otpDeliveryStatus)

        viewModel.verifyOtp("pessoa@matcher.invalid", "123456")
        advanceUntilIdle()

        assertEquals(MatcherSession.SignedIn("user-test"), viewModel.uiState.value.session)
        assertNull(viewModel.uiState.value.otpRequestedFor)
        assertNull(viewModel.uiState.value.otpDeliveryStatus)
        assertEquals(0, viewModel.uiState.value.otpResendSecondsRemaining)
        assertEquals(SignedInStage.Onboarding, viewModel.uiState.value.signedInStage)
    }

    @Test
    fun malformedOtpIsRejectedBeforeCallingAuthProvider() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)
        runCurrent()

        viewModel.verifyOtp("pessoa@matcher.invalid", "12345")
        advanceUntilIdle()

        assertNull(auth.verifiedOtp)
        assertEquals("Digite o código de 6 dígitos.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun onboardingSendsBirthYearThenLoadsUnverifiedHome() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-test"))
        val profiles = FakeProfileGateway()
        val age = FakeAgeVerificationGateway(onboardingRequiredSnapshot())
        val viewModel = viewModel(auth, profiles, FakeChatGateway(), age = age)
        advanceUntilIdle()
        assertEquals(SignedInStage.Onboarding, viewModel.uiState.value.signedInStage)

        age.snapshot = activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)
        viewModel.completeOnboarding(
            displayName = "Pessoa Teste",
            birthYear = 1995,
            bio = "Bio sintética",
            intent = "Conhecer pessoas",
            genderIdentityIds = setOf("non_binary"),
            genderSelfDescription = "",
            genderVisible = true,
            lookingForGenderIds = setOf("everyone"),
            termsAccepted = true,
        )
        advanceUntilIdle()

        assertEquals(1995, profiles.lastOnboarding?.birthYear)
        assertEquals("dev-2026-07", profiles.lastOnboarding?.termsVersion)
        assertEquals(true, profiles.lastOnboarding?.termsAccepted)
        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertNotNull(viewModel.uiState.value.profile)
    }

    @Test
    fun pendingVerificationDoesNotBlockDiscoveryOrChat() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val chat = FakeChatGateway()
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            chat = chat,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.Pending)),
        )

        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertNotNull(viewModel.uiState.value.profile)
        assertEquals(1, profiles.discoveryCalls)
        assertEquals(1, chat.snapshotCalls)
    }

    @Test
    fun verifiedAccountAlsoLoadsHome() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val chat = FakeChatGateway()
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            chat = chat,
            age = FakeAgeVerificationGateway(activeVerifiedSnapshot()),
        )

        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertEquals("Pessoa Teste", viewModel.uiState.value.profile?.displayName)
        assertEquals(1, profiles.discoveryCalls)
        assertEquals(1, chat.snapshotCalls)
    }

    @Test
    fun providerReturnRefreshesStatusWithoutGrantingVerification() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val chat = FakeChatGateway()
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.Pending))
        val viewModel = viewModel(
            FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles,
            chat,
            age = age,
        )
        advanceUntilIdle()
        val statusCallsBeforeReturn = age.statusCalls

        viewModel.onAgeVerificationReturn(1)
        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertEquals(statusCallsBeforeReturn + 1, age.statusCalls)
        assertEquals(AgeVerificationStatus.Pending, viewModel.uiState.value.ageVerificationStatus)
        assertTrue(profiles.discoveryCalls >= 2)
        assertTrue(chat.snapshotCalls >= 2)

        viewModel.onAgeVerificationReturn(1)
        advanceUntilIdle()
        assertEquals(statusCallsBeforeReturn + 1, age.statusCalls)
    }

    @Test
    fun verifiedServerStatusAfterReturnUpdatesBadgeState() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.Pending))
        val viewModel = viewModel(
            FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles,
            FakeChatGateway(),
            age = age,
        )
        advanceUntilIdle()

        age.snapshot = activeVerifiedSnapshot()
        viewModel.onAgeVerificationReturn(1)
        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertEquals(AgeVerificationStatus.Verified, viewModel.uiState.value.ageVerificationStatus)
        assertNotNull(viewModel.uiState.value.profile)
    }

    @Test
    fun optionalVerificationRefreshFailureKeepsActiveProfileUsable() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val age = FakeAgeVerificationGateway(
            activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted),
        )
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            age = age,
        )
        advanceUntilIdle()
        viewModel.openAgeVerification()
        age.statusError = IllegalStateException("synthetic status outage")

        viewModel.refreshAgeVerificationStatus()
        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertNotNull(viewModel.uiState.value.profile)
        assertTrue(viewModel.uiState.value.ageVerificationOpen)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.verificationLoading)
    }

    @Test
    fun profilePhotoUploadUpdatesPendingPhotoWithoutGrantingVerification() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted))
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            age = age,
        )
        advanceUntilIdle()
        val jpegBytes = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xD9.toByte(),
        )

        viewModel.submitProfilePhoto(jpegBytes)
        advanceUntilIdle()

        assertTrue(requireNotNull(profiles.lastSubmittedPhoto).contentEquals(jpegBytes))
        assertEquals("pending", viewModel.uiState.value.profile?.avatarModerationStatus)
        assertEquals("user-test/pending-photo.jpg", viewModel.uiState.value.profile?.avatarCandidatePath)
        assertNull(viewModel.uiState.value.profile?.avatarPath)
        assertEquals(AgeVerificationStatus.NotStarted, viewModel.uiState.value.ageVerificationStatus)
        assertFalse(viewModel.uiState.value.profile?.verified ?: true)
        assertFalse(viewModel.uiState.value.photoLoading)
        assertEquals(0, age.createSessionCalls)
        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
    }

    @Test
    fun receivedPrivateAlbumRequiresExplicitRevealAndClearsBytesOnClose() = runTest(dispatcher) {
        val ownerId = "00000000-0000-4000-8000-000000000222"
        val itemId = "00000000-0000-4000-8000-000000000223"
        val albums = FakePrivateAlbumGateway().apply {
            sharedWithMe += SharedPrivateAlbum(
                albumId = "00000000-0000-4000-8000-000000000221",
                ownerId = ownerId,
                ownerDisplayName = "Pessoa destino",
                itemCount = 1,
                grantedAt = "2026-07-31T12:00:00Z",
            )
            receivedContent = PrivateAlbumContent(
                albumId = "00000000-0000-4000-8000-000000000221",
                ownerId = ownerId,
                items = listOf(PrivateAlbumItem(itemId, 0)),
            )
            bytesByItemId[itemId] = byteArrayOf(1, 2, 3)
        }
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()

        viewModel.openReceivedPrivateAlbum(ownerId, "Pessoa destino")

        assertTrue(viewModel.uiState.value.privateAlbum.destination is PrivateAlbumDestination.Warning)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())

        viewModel.revealReceivedPrivateAlbum()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.privateAlbum.destination is PrivateAlbumDestination.Received)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.containsKey(itemId))

        viewModel.closePrivateAlbum()

        assertNull(viewModel.uiState.value.privateAlbum.destination)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun closingAlbumCancelsRevealAndLateResponseCannotReopenIt() = runTest(dispatcher) {
        val shared = syntheticSharedAlbum()
        val item = PrivateAlbumItem("00000000-0000-4000-8000-000000000263", 0)
        val gate = CompletableDeferred<Unit>()
        val albums = FakePrivateAlbumGateway().apply {
            sharedWithMe += shared
            receivedContent = PrivateAlbumContent(shared.albumId, shared.ownerId, listOf(item))
            bytesByItemId[item.itemId] = byteArrayOf(7, 8, 9)
            getPrivateAlbumGate = gate
            ignoreGetPrivateAlbumCancellation = true
        }
        val viewModel = activeViewModel(albums = albums)
        advanceUntilIdle()

        viewModel.openReceivedPrivateAlbum(shared)
        viewModel.revealReceivedPrivateAlbum()
        runCurrent()
        viewModel.closePrivateAlbum()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.privateAlbum.destination)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun changingSessionDiscardsLatePrivateAlbumResponseFromPreviousUser() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-one"))
        val shared = syntheticSharedAlbum()
        val item = PrivateAlbumItem("00000000-0000-4000-8000-000000000273", 0)
        val gate = CompletableDeferred<Unit>()
        val albums = FakePrivateAlbumGateway().apply {
            sharedWithMe += shared
            receivedContent = PrivateAlbumContent(shared.albumId, shared.ownerId, listOf(item))
            bytesByItemId[item.itemId] = byteArrayOf(10, 11, 12)
            getPrivateAlbumGate = gate
            ignoreGetPrivateAlbumCancellation = true
        }
        val viewModel = viewModel(
            auth = auth,
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()

        viewModel.openReceivedPrivateAlbum(shared)
        viewModel.revealReceivedPrivateAlbum()
        runCurrent()
        auth.session.value = MatcherSession.SignedIn("user-two")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MatcherSession.SignedIn("user-two"), viewModel.uiState.value.session)
        assertNull(viewModel.uiState.value.privateAlbum.destination)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun downloadedBytesReturnedAfterSessionCancellationAreZeroedBeforeDiscard() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-one"))
        val shared = syntheticSharedAlbum()
        val item = PrivateAlbumItem("00000000-0000-4000-8000-000000000274", 0)
        val albums = FakePrivateAlbumGateway().apply {
            sharedWithMe += shared
            receivedContent = PrivateAlbumContent(shared.albumId, shared.ownerId, listOf(item))
            bytesByItemId[item.itemId] = byteArrayOf(21, 22, 23)
            holdDownloadNonCancellable = true
        }
        val viewModel = viewModel(
            auth = auth,
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        viewModel.openReceivedPrivateAlbum(shared)
        viewModel.revealReceivedPrivateAlbum()
        runCurrent()

        auth.session.value = MatcherSession.SignedIn("user-two")
        runCurrent()
        albums.completeHeldDownload()
        advanceUntilIdle()

        assertTrue(requireNotNull(albums.lastReturnedDownload).all { it == 0.toByte() })
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun changingSessionCancelsInitialAlbumSummaryLoadWithoutStoppingSessionCollection() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-one"))
        val gate = CompletableDeferred<Unit>()
        val albums = FakePrivateAlbumGateway().apply {
            getMyPrivateAlbumGate = gate
            ignoreGetMyPrivateAlbumCancellation = true
        }
        val viewModel = viewModel(
            auth = auth,
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        runCurrent()
        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)

        auth.session.value = MatcherSession.SignedIn("user-two")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MatcherSession.SignedIn("user-two"), viewModel.uiState.value.session)
        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun sharedAlbumOpensDirectlyFromSharedWithMeWithoutSelectedDiscoveryProfile() = runTest(dispatcher) {
        val shared = syntheticSharedAlbum().copy(ownerDisplayName = "Fora da descoberta")
        val albums = FakePrivateAlbumGateway().apply { sharedWithMe += shared }
        val viewModel = activeViewModel(albums = albums)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.discovery.profiles.isEmpty())
        viewModel.openReceivedPrivateAlbum(shared)

        assertEquals(
            PrivateAlbumDestination.Warning(shared.albumId, shared.ownerId, "Fora da descoberta"),
            viewModel.uiState.value.privateAlbum.destination,
        )
    }

    @Test
    fun revokedGrantRemainsKnownSoOwnerCanGrantAgainOutsideDiscovery() = runTest(dispatcher) {
        val recipientId = "00000000-0000-4000-8000-000000000282"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum("00000000-0000-4000-8000-000000000281", "active", 1)
            grants += PrivateAlbumGrant(recipientId, "Contato conhecido", "2026-07-31T12:00:00Z")
        }
        val viewModel = activeViewModel(albums = albums)
        advanceUntilIdle()

        viewModel.togglePrivateAlbumGrant(recipientId, currentlyShared = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.privateAlbum.myGrants.isEmpty())
        assertEquals("Contato conhecido", viewModel.uiState.value.privateAlbum.knownRecipients[recipientId])
        assertEquals("00000000-0000-4000-8000-000000000281", albums.revokedAlbumId)
    }

    @Test
    fun bulkRevokeRemovesOnlySelectedRecipientsAndReloadsAuthoritativeList() = runTest(dispatcher) {
        val firstId = "00000000-0000-4000-8000-000000000286"
        val secondId = "00000000-0000-4000-8000-000000000287"
        val untouchedId = "00000000-0000-4000-8000-000000000288"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum("00000000-0000-4000-8000-000000000289", "active", 0)
            grants += PrivateAlbumGrant(firstId, "Contato A", "2026-07-31T12:00:00Z")
            grants += PrivateAlbumGrant(secondId, "Contato B", "2026-07-31T12:00:00Z")
            grants += PrivateAlbumGrant(untouchedId, "Contato C", "2026-07-31T12:00:00Z")
        }
        val viewModel = activeViewModel(albums = albums)
        advanceUntilIdle()

        viewModel.revokePrivateAlbumGrants(setOf(secondId, firstId))
        advanceUntilIdle()

        assertEquals(listOf(firstId, secondId), albums.revokedRecipientIds)
        assertEquals(listOf(untouchedId), viewModel.uiState.value.privateAlbum.myGrants.map { it.recipientId })
    }

    @Test
    fun bulkRevokeReloadsListAfterIntermediateFailure() = runTest(dispatcher) {
        val firstId = "00000000-0000-4000-8000-000000000291"
        val failingId = "00000000-0000-4000-8000-000000000292"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum("00000000-0000-4000-8000-000000000293", "active", 0)
            grants += PrivateAlbumGrant(firstId, "Contato A", "2026-07-31T12:00:00Z")
            grants += PrivateAlbumGrant(failingId, "Contato B", "2026-07-31T12:00:00Z")
            revokeFailureRecipientId = failingId
        }
        val viewModel = activeViewModel(albums = albums)
        advanceUntilIdle()

        viewModel.revokePrivateAlbumGrants(setOf(failingId, firstId))
        advanceUntilIdle()

        assertEquals(listOf(firstId, failingId), albums.revokedRecipientIds)
        assertEquals(listOf(failingId), viewModel.uiState.value.privateAlbum.myGrants.map { it.recipientId })
        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun grantStartedForAlbumACannotRetargetReplacementAlbumBAfterSessionChange() = runTest(dispatcher) {
        val albumA = PrivateAlbum("00000000-0000-4000-8000-000000000283", "active", 1)
        val albumB = PrivateAlbum("00000000-0000-4000-8000-000000000284", "active", 1)
        val recipientId = "00000000-0000-4000-8000-000000000285"
        val gate = CompletableDeferred<Unit>()
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-a"))
        val albums = FakePrivateAlbumGateway().apply { album = albumA }
        val viewModel = viewModel(
            auth = auth,
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        albums.grantGate = gate
        albums.ignoreGrantCancellation = true

        viewModel.togglePrivateAlbumGrant(recipientId, currentlyShared = false)
        runCurrent()
        albums.album = albumB
        auth.session.value = MatcherSession.SignedIn("user-b")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(albumA.albumId, albums.grantedAlbumId)
        assertEquals(albumB.albumId, viewModel.uiState.value.privateAlbum.myAlbum?.albumId)
    }

    @Test
    fun staleDiscoveryCursorAlwaysReplacesPreviousProfilesWithFreshFirstPage() = runTest(dispatcher) {
        val oldFirst = testProfile().copy(id = "00000000-0000-4000-8000-000000000291", displayName = "Anterior")
        val freshFirst = testProfile().copy(id = "00000000-0000-4000-8000-000000000292", displayName = "Novo filtro")
        val profiles = FakeProfileGateway(initialProfile = testProfile()).apply {
            firstPage = DiscoveryPage(listOf(oldFirst), oldFirst.id, preferenceCursorVersion = 1)
        }
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        profiles.firstPage = DiscoveryPage(listOf(freshFirst), null, preferenceCursorVersion = 2)
        profiles.failNextCursorAsStale = true

        viewModel.loadMoreDiscovery()
        advanceUntilIdle()

        assertEquals(listOf(freshFirst.id), viewModel.uiState.value.discovery.profiles.map { it.id })
        assertEquals(2L, viewModel.uiState.value.discovery.preferenceCursorVersion)
    }

    @Test
    fun lateDiscoveryPageFromPreviousSessionCannotPopulateNextUser() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-a"))
        val firstA = testProfile().copy(
            id = "00000000-0000-4000-8000-000000000301",
            displayName = "Sessão A",
        )
        val nextA = testProfile().copy(
            id = "00000000-0000-4000-8000-000000000302",
            displayName = "Página tardia A",
        )
        val firstB = testProfile().copy(
            id = "00000000-0000-4000-8000-000000000303",
            displayName = "Sessão B",
        )
        val gate = CompletableDeferred<Unit>()
        val profiles = FakeProfileGateway(initialProfile = testProfile()).apply {
            firstPage = DiscoveryPage(listOf(firstA), firstA.id, preferenceCursorVersion = 1)
            nextPage = DiscoveryPage(listOf(nextA), null, preferenceCursorVersion = 1)
        }
        val viewModel = viewModel(
            auth = auth,
            profiles = profiles,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        profiles.nextPageGate = gate
        profiles.ignoreNextPageCancellation = true

        viewModel.loadMoreDiscovery()
        runCurrent()
        profiles.firstPage = DiscoveryPage(listOf(firstB), null, preferenceCursorVersion = 2)
        auth.session.value = MatcherSession.SignedIn("user-b")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MatcherSession.SignedIn("user-b"), viewModel.uiState.value.session)
        assertEquals(listOf(firstB.id), viewModel.uiState.value.discovery.profiles.map { it.id })
        assertFalse(viewModel.uiState.value.discovery.profiles.any { it.id == nextA.id })
    }

    @Test
    fun privateAlbumUploadCreatesAlbumAndMakesPhotoImmediatelyAvailableToOwner() = runTest(dispatcher) {
        val albums = FakePrivateAlbumGateway()
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val expectedJpeg = jpeg.copyOf()

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.privateAlbum.myAlbum)
        assertEquals(viewModel.uiState.value.privateAlbum.myAlbum?.albumId, albums.uploadedAlbumId)
        assertEquals(1, viewModel.uiState.value.privateAlbum.myItems.size)
        assertEquals("available", viewModel.uiState.value.privateAlbum.myItems.single().itemStatus)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.values.single().contentEquals(expectedJpeg))
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    @Test
    fun finalizedPrivateAlbumUploadSurvivesPreviewRefreshFailureWithoutRetrying() = runTest(dispatcher) {
        val albumId = "00000000-0000-4000-8000-000000000318"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumId, "active", 0)
            downloadFailureAfterUpload = IllegalStateException("synthetic preview failure")
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertEquals(listOf(albumId), albums.uploadAttempts)
        assertEquals(
            "Foto adicionada. Não foi possível carregar a prévia agora; " +
                "abra o álbum novamente para tentar.",
            viewModel.uiState.value.errorMessage,
        )
        assertEquals(albumId, viewModel.uiState.value.privateAlbum.myAlbum?.albumId)
        assertEquals(1, viewModel.uiState.value.privateAlbum.myAlbum?.itemCount)
        assertEquals(1, viewModel.uiState.value.privateAlbum.myItems.size)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
        assertFalse(viewModel.uiState.value.privateAlbum.loading)
        assertTrue(jpeg.all { it == 0.toByte() })

        viewModel.closePrivateAlbum()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()

        assertEquals(1, albums.uploadAttempts.size)
        assertEquals(1, viewModel.uiState.value.privateAlbum.visibleBytes.size)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun previewRefreshCancellationIsNotConvertedIntoRecoverablePreviewMessage() = runTest(dispatcher) {
        val albumId = "00000000-0000-4000-8000-000000000319"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumId, "active", 0)
            downloadFailureAfterUpload = CancellationException("synthetic cancellation")
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertEquals(listOf(albumId), albums.uploadAttempts)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.privateAlbum.loading)
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    @Test
    fun postFinalizeAccessFailureClearsPrivateContentAndIsNotReportedAsPreviewFailure() = runTest(dispatcher) {
        val albumId = "00000000-0000-4000-8000-000000000320"
        val existingItem = PrivateAlbumItem(
            itemId = "20000000-0000-4000-8000-000000000320",
            position = 0,
        )
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumId, "active", 1)
            items += existingItem
            bytesByItemId[existingItem.itemId] = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(),
            )
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.privateAlbum.visibleBytes.size)
        albums.getMyPrivateAlbumFailureAfterUpload =
            IllegalStateException("PRIVATE_ALBUM_NOT_AVAILABLE")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertEquals(1, albums.uploadAttempts.size)
        assertEquals(
            "Este álbum privado não está mais disponível para você.",
            viewModel.uiState.value.errorMessage,
        )
        assertNull(viewModel.uiState.value.privateAlbum.destination)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleItems.isEmpty())
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    @Test
    fun previewFailureKeepsAuthoritativeCountAfterConcurrentDeletion() = runTest(dispatcher) {
        val albumId = "00000000-0000-4000-8000-000000000321"
        val first = PrivateAlbumItem("20000000-0000-4000-8000-000000000321", 0)
        val second = PrivateAlbumItem("20000000-0000-4000-8000-000000000322", 1)
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumId, "active", 2)
            items += listOf(first, second)
            bytesByItemId[first.itemId] = jpegBytes.copyOf()
            bytesByItemId[second.itemId] = jpegBytes.copyOf()
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        albums.removeFirstItemBeforePostUploadRefresh = true
        albums.downloadFailureAfterUpload = IllegalStateException("synthetic preview failure")

        viewModel.uploadPrivateAlbumPhoto(jpegBytes.copyOf())
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.privateAlbum.myAlbum?.itemCount)
        assertEquals(2, viewModel.uiState.value.privateAlbum.myItems.size)
        assertEquals(1, albums.uploadAttempts.size)
    }

    @Test
    fun staleUploadFromAlbumAIsNeverRedirectedToReplacementAlbumB() = runTest(dispatcher) {
        val albumA = "00000000-0000-4000-8000-000000000311"
        val albumB = "00000000-0000-4000-8000-000000000312"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumA, "active", 0)
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        assertEquals(albumA, viewModel.uiState.value.privateAlbum.myAlbum?.albumId)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        albums.album = PrivateAlbum(albumB, "active", 0)
        advanceUntilIdle()

        assertEquals(listOf(albumA), albums.uploadAttempts)
        assertNull(albums.uploadedAlbumId)
        assertEquals(0, albums.createAlbumCalls)
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    @Test
    fun rejectedPrivateAlbumUploadWipesSelectedPhotoBytes() = runTest(dispatcher) {
        val albums = FakePrivateAlbumGateway()
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)

        assertTrue(jpeg.all { it == 0.toByte() })
        assertTrue(albums.uploadAttempts.isEmpty())
        assertEquals(0, albums.createAlbumCalls)
    }

    @Test
    fun storagePolicyFailureKeepsAlbumOpenAndShowsRecoverableSafeMessage() = runTest(dispatcher) {
        val albumId = "00000000-0000-4000-8000-000000000317"
        val hiddenStorageDetails = "PRIVATE_ALBUM_STORAGE_ACCESS_DENIED: hidden/object/path"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumId, "active", 0)
            uploadFailure = IllegalStateException("upload failed", IllegalStateException(hiddenStorageDetails))
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertEquals(PrivateAlbumDestination.Mine, viewModel.uiState.value.privateAlbum.destination)
        assertEquals(
            "Não foi possível enviar a foto privada agora. Tente novamente; " +
                "se continuar, saia e entre na conta.",
            viewModel.uiState.value.errorMessage,
        )
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains(hiddenStorageDetails))
        assertTrue(jpeg.all { it == 0.toByte() })
    }

    @Test
    fun ownerSummaryRetriesWhenAlbumChangesBetweenUnboundReads() = runTest(dispatcher) {
        val albumA = "00000000-0000-4000-8000-000000000313"
        val albumB = "00000000-0000-4000-8000-000000000314"
        val itemA = PrivateAlbumItem("00000000-0000-4000-8000-000000000315", 0)
        val itemB = PrivateAlbumItem("00000000-0000-4000-8000-000000000316", 0)
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(albumA, "active", 1)
            items += itemA
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        assertEquals(albumA, viewModel.uiState.value.privateAlbum.myAlbum?.albumId)
        assertEquals(listOf(itemA.itemId), viewModel.uiState.value.privateAlbum.myItems.map { it.itemId })
        albums.items.clear()
        albums.items += itemB
        albums.switchAlbumAfterNextGet = PrivateAlbum(albumB, "active", 1)

        viewModel.refreshPrivateAlbumAccess()
        advanceUntilIdle()

        assertEquals(albumB, viewModel.uiState.value.privateAlbum.myAlbum?.albumId)
        assertEquals(listOf(itemB.itemId), viewModel.uiState.value.privateAlbum.myItems.map { it.itemId })
    }

    @Test
    fun deletingAlbumPinsRequestToAlbumCurrentlyShownByTheOwner() = runTest(dispatcher) {
        val expectedAlbumId = "00000000-0000-4000-8000-000000000321"
        val albums = FakePrivateAlbumGateway().apply {
            album = PrivateAlbum(expectedAlbumId, "active", 0)
        }
        val viewModel = activeViewModel(albums)
        advanceUntilIdle()
        viewModel.openMyPrivateAlbum()
        advanceUntilIdle()

        viewModel.deleteMyPrivateAlbum()
        advanceUntilIdle()

        assertEquals(expectedAlbumId, albums.deletedAlbumId)
        assertNull(viewModel.uiState.value.privateAlbum.myAlbum)
    }

    @Test
    fun revokedAlbumIsRemovedFromMemoryOnServerRevalidation() = runTest(dispatcher) {
        val ownerId = "00000000-0000-4000-8000-000000000242"
        val itemId = "00000000-0000-4000-8000-000000000243"
        val albums = FakePrivateAlbumGateway().apply {
            sharedWithMe += SharedPrivateAlbum(
                albumId = "00000000-0000-4000-8000-000000000241",
                ownerId = ownerId,
                ownerDisplayName = "Pessoa destino",
                itemCount = 1,
                grantedAt = "2026-07-31T12:00:00Z",
            )
            receivedContent = PrivateAlbumContent(
                albumId = "00000000-0000-4000-8000-000000000241",
                ownerId = ownerId,
                items = listOf(PrivateAlbumItem(itemId, 0)),
            )
            bytesByItemId[itemId] = byteArrayOf(4, 5, 6)
        }
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            albums = albums,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        viewModel.openReceivedPrivateAlbum(ownerId, "Pessoa destino")
        viewModel.revealReceivedPrivateAlbum()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isNotEmpty())

        albums.receivedContent = null
        viewModel.revalidateReceivedPrivateAlbum()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.privateAlbum.destination)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.isEmpty())
    }

    @Test
    fun genderPreferenceUpdateIsPrivateStateAndReloadsDiscovery() = runTest(dispatcher) {
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = profiles,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        val callsBefore = profiles.discoveryCalls

        viewModel.updateGenderSettings(
            genderIdentityIds = setOf("non_binary", "self_described"),
            genderSelfDescription = "Identidade sintética",
            genderVisible = false,
            lookingForGenderIds = setOf("woman", "man"),
        )
        advanceUntilIdle()

        assertEquals(listOf("man", "woman"), viewModel.uiState.value.genderSettings?.lookingForGenderIds)
        assertFalse(viewModel.uiState.value.genderSettings?.genderVisible ?: true)
        assertEquals(callsBefore + 1, profiles.discoveryCalls)
    }

    @Test
    fun lateGenderUpdateFromPreviousSessionCannotOverwriteNextUser() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-a"))
        val gate = CompletableDeferred<Unit>()
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val viewModel = viewModel(
            auth = auth,
            profiles = profiles,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()
        profiles.updateGenderGate = gate
        profiles.ignoreUpdateGenderCancellation = true

        viewModel.updateGenderSettings(
            genderIdentityIds = setOf("man"),
            genderSelfDescription = "",
            genderVisible = true,
            lookingForGenderIds = setOf("woman"),
        )
        runCurrent()
        val settingsB = GenderSettings(
            genderIdentityIds = listOf("woman"),
            genderVisible = true,
            lookingForGenderIds = listOf("everyone"),
            preferenceCursorVersion = 9,
        )
        profiles.genderSettings = settingsB
        profiles.profile = testProfile().copy(
            genderIdentityIds = settingsB.genderIdentityIds,
            genderVisible = settingsB.genderVisible,
        )
        auth.session.value = MatcherSession.SignedIn("user-b")
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MatcherSession.SignedIn("user-b"), viewModel.uiState.value.session)
        assertEquals(settingsB, viewModel.uiState.value.genderSettings)
        assertEquals(listOf("woman"), viewModel.uiState.value.profile?.genderIdentityIds)
    }

    @Test
    fun ageSessionRequiresAffirmativeDiditProcessingConsent() = runTest(dispatcher) {
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted))
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            age = age,
        )
        advanceUntilIdle()

        viewModel.openAgeVerification()

        viewModel.startAgeVerification()
        advanceUntilIdle()

        assertEquals(0, age.createSessionCalls)
        assertFalse(viewModel.uiState.value.ageVerificationConsentGranted)
        assertEquals(
            AGE_VERIFICATION_CONSENT_REQUIRED_MESSAGE,
            viewModel.uiState.value.errorMessage,
        )

        viewModel.setAgeVerificationConsent(true)

        assertTrue(viewModel.uiState.value.ageVerificationConsentGranted)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun ageSessionEmitsTrustedHostedUrlOnlyOnceForDoubleTap() = runTest(dispatcher) {
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted))
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            age = age,
        )
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.openAgeVerification()
        viewModel.setAgeVerificationConsent(true)

        viewModel.startAgeVerification()
        viewModel.startAgeVerification()
        advanceUntilIdle()

        assertEquals(1, age.createSessionCalls)
        assertEquals(
            RemoteMatcherEffect.OpenAgeVerification(age.session.verificationUrl),
            effect.await(),
        )
        assertEquals(AgeVerificationStatus.Pending, viewModel.uiState.value.ageVerificationStatus)
    }

    @Test
    fun untrustedHostedUrlIsRejectedBeforeBrowserEffect() = runTest(dispatcher) {
        val age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)).apply {
            session = AgeVerificationSession("https://attacker.invalid/session", "2026-08-01T00:00:00Z")
        }
        val viewModel = viewModel(
            auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
            profiles = FakeProfileGateway(initialProfile = testProfile()),
            age = age,
        )
        advanceUntilIdle()
        viewModel.openAgeVerification()
        viewModel.setAgeVerificationConsent(true)

        viewModel.startAgeVerification()
        advanceUntilIdle()

        assertEquals(1, age.createSessionCalls)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals(AgeVerificationStatus.NotStarted, viewModel.uiState.value.ageVerificationStatus)
    }

    @Test
    fun signedInUserChangeLoadsTheNextUnverifiedAccountWithoutGate() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-one"))
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val age = FakeAgeVerificationGateway(activeVerifiedSnapshot())
        val viewModel = viewModel(auth, profiles, FakeChatGateway(), age = age)
        advanceUntilIdle()
        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)

        age.snapshot = activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)
        auth.session.value = MatcherSession.SignedIn("user-two")
        advanceUntilIdle()

        assertEquals(SignedInStage.Active, viewModel.uiState.value.signedInStage)
        assertNotNull(viewModel.uiState.value.profile)
        assertEquals(AgeVerificationStatus.NotStarted, viewModel.uiState.value.ageVerificationStatus)
    }

    @Test
    fun accountDeletionIsRequestedBeforeTheLocalSessionIsClosed() = runTest(dispatcher) {
        val auth = FakeAuthGateway(MatcherSession.SignedIn("user-test"))
        val profiles = FakeProfileGateway(initialProfile = testProfile())
        val viewModel = viewModel(
            auth = auth,
            profiles = profiles,
            age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
        )
        advanceUntilIdle()

        viewModel.deleteAccount()
        advanceUntilIdle()

        assertEquals(1, profiles.accountDeletionRequests)
        assertEquals(MatcherSession.SignedOut, auth.session.value)
    }

    @Test
    fun diditUrlValidationRequiresExactHttpsHostAndStandardPort() {
        assertTrue(isTrustedAgeVerificationUrl("https://verify.didit.me/session/synthetic"))
        assertTrue(isTrustedAgeVerificationUrl("https://verify.didit.me:443/session/synthetic"))
        assertFalse(isTrustedAgeVerificationUrl("http://verify.didit.me/session/synthetic"))
        assertFalse(isTrustedAgeVerificationUrl("https://verify.didit.me.attacker.invalid/session"))
        assertFalse(isTrustedAgeVerificationUrl("https://user@verify.didit.me/session"))
        assertFalse(isTrustedAgeVerificationUrl("https://verify.didit.me:444/session"))
    }

    private fun viewModel(
        auth: FakeAuthGateway = FakeAuthGateway(),
        profiles: FakeProfileGateway = FakeProfileGateway(),
        chat: FakeChatGateway = FakeChatGateway(),
        albums: FakePrivateAlbumGateway = FakePrivateAlbumGateway(),
        age: FakeAgeVerificationGateway = FakeAgeVerificationGateway(onboardingRequiredSnapshot()),
    ) = RemoteMatcherViewModel(auth, profiles, chat, albums, age)

    private fun activeViewModel(
        albums: FakePrivateAlbumGateway = FakePrivateAlbumGateway(),
    ) = viewModel(
        auth = FakeAuthGateway(MatcherSession.SignedIn("user-test")),
        profiles = FakeProfileGateway(initialProfile = testProfile()),
        albums = albums,
        age = FakeAgeVerificationGateway(activeUnverifiedSnapshot(AgeVerificationStatus.NotStarted)),
    )
}

private class FakeAuthGateway(
    initialSession: MatcherSession = MatcherSession.SignedOut,
) : AuthGateway {
    override val session = MutableStateFlow(initialSession)
    var requestedEmail: String? = null
    var verifiedEmail: String? = null
    var verifiedOtp: String? = null
    var requestCalls = 0
    var verifyCalls = 0
    var requestGate: CompletableDeferred<Unit>? = null
    var verifyGate: CompletableDeferred<Unit>? = null
    var requestFailure: Exception? = null
    var signedInAfterVerify: MatcherSession.SignedIn? = null

    override suspend fun requestEmailOtp(email: String) {
        requestCalls += 1
        requestedEmail = email
        requestGate?.await()
        requestFailure?.let { throw it }
    }

    override suspend fun verifyEmailOtp(email: String, token: String) {
        verifyCalls += 1
        verifiedEmail = email
        verifiedOtp = token
        verifyGate?.await()
        signedInAfterVerify?.let { session.value = it }
    }

    override suspend fun signOut() {
        session.value = MatcherSession.SignedOut
    }
}

private class FakeProfileGateway(
    initialProfile: RemoteProfile? = null,
) : ProfileGateway {
    var lastOnboarding: CompleteOnboardingRequest? = null
    var lastSubmittedPhoto: ByteArray? = null
    var profile: RemoteProfile? = initialProfile
    var discoveryCalls = 0
    var firstPage = DiscoveryPage(emptyList(), null)
    var nextPage = DiscoveryPage(emptyList(), null)
    var failNextCursorAsStale = false
    var nextPageGate: CompletableDeferred<Unit>? = null
    var ignoreNextPageCancellation = false
    var updateGenderGate: CompletableDeferred<Unit>? = null
    var ignoreUpdateGenderCancellation = false
    var accountDeletionRequests = 0
    var genderSettings = GenderSettings(
        genderIdentityIds = listOf("non_binary"),
        genderVisible = true,
        lookingForGenderIds = listOf("everyone"),
        preferenceCursorVersion = 1,
    )

    override suspend fun completeOnboarding(
        request: CompleteOnboardingRequest,
    ): CompleteOnboardingResponse {
        lastOnboarding = request
        genderSettings = GenderSettings(
            genderIdentityIds = request.genderIdentityIds,
            genderSelfDescription = request.genderSelfDescription,
            genderVisible = request.genderVisible,
            lookingForGenderIds = request.lookingForGenderIds,
            preferenceCursorVersion = 1,
        )
        profile = testProfile(displayName = request.displayName, bio = request.bio, intent = request.intent).copy(
            genderIdentityIds = request.genderIdentityIds,
            genderSelfDescription = request.genderSelfDescription,
            genderVisible = request.genderVisible,
        )
        return CompleteOnboardingResponse("user-test", "active", 31)
    }

    override suspend fun currentProfile(): RemoteProfile? = profile

    override suspend fun discoveryPage(cursor: String?, pageSize: Int): DiscoveryPage {
        discoveryCalls += 1
        return firstPage
    }

    override suspend fun discoveryPage(
        cursor: String,
        preferenceCursorVersion: Long,
        pageSize: Int,
    ): DiscoveryPage {
        discoveryCalls += 1
        nextPageGate?.let { gate ->
            if (ignoreNextPageCancellation) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
        if (failNextCursorAsStale) {
            failNextCursorAsStale = false
            error("DISCOVERY_CURSOR_STALE")
        }
        return nextPage
    }

    override suspend fun getGenderSettings(): GenderSettings = genderSettings

    override suspend fun updateGenderSettings(request: UpdateGenderSettingsRequest): GenderSettings {
        val update = suspend {
            updateGenderGate?.await()
            genderSettings = GenderSettings(
                genderIdentityIds = request.genderIdentityIds,
                genderSelfDescription = request.genderSelfDescription,
                genderVisible = request.genderVisible,
                lookingForGenderIds = request.lookingForGenderIds,
                preferenceCursorVersion = genderSettings.preferenceCursorVersion + 1,
            )
            genderSettings
        }
        return if (ignoreUpdateGenderCancellation) {
            withContext(NonCancellable) { update() }
        } else {
            update()
        }
    }

    override suspend fun submitProfilePhoto(jpegBytes: ByteArray): RemoteProfile {
        lastSubmittedPhoto = jpegBytes.copyOf()
        val updatedProfile = requireNotNull(profile).copy(
            avatarCandidatePath = "user-test/pending-photo.jpg",
            avatarModerationStatus = "pending",
            avatarUrl = "https://storage.matcher.invalid/pending-photo.jpg",
        )
        profile = updatedProfile
        return updatedProfile
    }

    override suspend fun requestAccountDeletion(): Boolean {
        accountDeletionRequests += 1
        return true
    }
}

private class FakePrivateAlbumGateway : PrivateAlbumGateway {
    var album: PrivateAlbum? = null
    var items = mutableListOf<PrivateAlbumItem>()
    var grants = mutableListOf<PrivateAlbumGrant>()
    var sharedWithMe = mutableListOf<SharedPrivateAlbum>()
    var receivedContent: PrivateAlbumContent? = null
    var switchAlbumAfterNextGet: PrivateAlbum? = null
    var getMyPrivateAlbumGate: CompletableDeferred<Unit>? = null
    var ignoreGetMyPrivateAlbumCancellation = false
    var getPrivateAlbumGate: CompletableDeferred<Unit>? = null
    var ignoreGetPrivateAlbumCancellation = false
    val bytesByItemId = mutableMapOf<String, ByteArray>()
    var reportedAlbumId: String? = null
    var deletedAlbumId: String? = null
    var uploadedAlbumId: String? = null
    var uploadFailure: Exception? = null
    var getMyPrivateAlbumFailureAfterUpload: Exception? = null
    var downloadFailureAfterUpload: Exception? = null
    var removeFirstItemBeforePostUploadRefresh = false
    val uploadAttempts = mutableListOf<String>()
    var createAlbumCalls = 0
    var grantedAlbumId: String? = null
    var revokedAlbumId: String? = null
    val revokedRecipientIds = mutableListOf<String>()
    var revokeFailureRecipientId: String? = null
    var grantGate: CompletableDeferred<Unit>? = null
    var ignoreGrantCancellation = false
    var holdDownloadNonCancellable = false
    var lastReturnedDownload: ByteArray? = null
    private var heldDownload: Pair<String, Continuation<ByteArray>>? = null

    override suspend fun createPrivateAlbum(
        contentPolicyVersion: String,
        contentPolicyAccepted: Boolean,
    ): PrivateAlbum {
        check(contentPolicyAccepted)
        createAlbumCalls += 1
        return album ?: PrivateAlbum("10000000-0000-4000-8000-000000000001", "active", 0).also {
            album = it
        }
    }

    override suspend fun getMyPrivateAlbum(): PrivateAlbum? {
        if (uploadedAlbumId != null) {
            if (removeFirstItemBeforePostUploadRefresh) {
                items.removeFirstOrNull()?.also { removed -> bytesByItemId.remove(removed.itemId)?.fill(0) }
                album = album?.copy(itemCount = items.size)
                removeFirstItemBeforePostUploadRefresh = false
            }
            getMyPrivateAlbumFailureAfterUpload?.let { failure ->
                getMyPrivateAlbumFailureAfterUpload = null
                throw failure
            }
        }
        getMyPrivateAlbumGate?.let { gate ->
            if (ignoreGetMyPrivateAlbumCancellation) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
        val snapshot = album?.copy(itemCount = items.size)
        switchAlbumAfterNextGet?.let { replacement ->
            album = replacement
            switchAlbumAfterNextGet = null
        }
        return snapshot
    }

    override suspend fun getMyPrivateAlbumItems(): List<PrivateAlbumItem> = items.toList()

    override suspend fun getPrivateAlbum(albumId: String): PrivateAlbumContent? {
        getPrivateAlbumGate?.let { gate ->
            if (ignoreGetPrivateAlbumCancellation) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
        return receivedContent?.takeIf { it.albumId == albumId }
    }

    override suspend fun getMyPrivateAlbumGrants(): List<PrivateAlbumGrant> = grants.toList()

    override suspend fun listPrivateAlbumsSharedWithMe(): List<SharedPrivateAlbum> = sharedWithMe.toList()

    override suspend fun uploadPrivateAlbumImage(albumId: String, jpegBytes: ByteArray): PrivateAlbumItem {
        uploadAttempts += albumId
        check(this.album?.albumId == albumId)
        uploadFailure?.let { throw it }
        uploadedAlbumId = albumId
        val item = PrivateAlbumItem(
            itemId = "20000000-0000-4000-8000-${(items.size + 1).toString().padStart(12, '0')}",
            position = items.size,
        )
        items += item
        bytesByItemId[item.itemId] = jpegBytes.copyOf()
        album = requireNotNull(album).copy(itemCount = items.size)
        return item
    }

    override suspend fun downloadPrivateAlbumImage(itemId: String): ByteArray {
        if (uploadedAlbumId != null) {
            downloadFailureAfterUpload?.let { failure ->
                downloadFailureAfterUpload = null
                throw failure
            }
        }
        if (holdDownloadNonCancellable) {
            return suspendCoroutine { continuation ->
                check(heldDownload == null)
                heldDownload = itemId to continuation
            }
        }
        return requireNotNull(bytesByItemId[itemId]).copyOf()
    }

    fun completeHeldDownload() {
        val (itemId, continuation) = requireNotNull(heldDownload)
        heldDownload = null
        val bytes = requireNotNull(bytesByItemId[itemId]).copyOf()
        lastReturnedDownload = bytes
        continuation.resume(bytes)
    }

    override suspend fun grantPrivateAlbumAccess(albumId: String, recipientId: String): Boolean {
        check(this.album?.albumId == albumId)
        grantedAlbumId = albumId
        grantGate?.let { gate ->
            if (ignoreGrantCancellation) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
        grants.removeAll { it.recipientId == recipientId }
        grants += PrivateAlbumGrant(recipientId, "Pessoa destino", "2026-07-31T12:00:00Z")
        return true
    }

    override suspend fun revokePrivateAlbumAccess(albumId: String, recipientId: String): Boolean {
        check(this.album?.albumId == albumId)
        revokedAlbumId = albumId
        revokedRecipientIds += recipientId
        if (recipientId == revokeFailureRecipientId) error("synthetic revoke failure")
        return grants.removeAll { it.recipientId == recipientId }
    }

    override suspend fun deletePrivateAlbumImage(itemId: String): Boolean {
        bytesByItemId.remove(itemId)
        return items.removeAll { it.itemId == itemId }
    }

    override suspend fun deletePrivateAlbum(albumId: String): Boolean {
        check(this.album?.albumId == albumId)
        deletedAlbumId = albumId
        album = null
        items.clear()
        grants.clear()
        bytesByItemId.clear()
        return true
    }

    override suspend fun reportPrivateAlbum(
        albumId: String,
        reason: PrivateAlbumReportReason,
        details: String,
        itemId: String?,
    ): String {
        reportedAlbumId = albumId
        sharedWithMe.removeAll { it.albumId == albumId }
        return "30000000-0000-4000-8000-000000000001"
    }
}

private class FakeAgeVerificationGateway(
    var snapshot: AgeVerificationSnapshot,
) : AgeVerificationGateway {
    var statusCalls = 0
    var createSessionCalls = 0
    var statusError: Exception? = null
    var session = AgeVerificationSession(
        verificationUrl = "https://verify.didit.me/session/synthetic",
        expiresAt = "2026-08-01T00:00:00Z",
    )

    override suspend fun getStatus(): AgeVerificationSnapshot {
        statusCalls += 1
        statusError?.let { throw it }
        return snapshot
    }

    override suspend fun createSession(): AgeVerificationSession {
        createSessionCalls += 1
        return session
    }
}

private class FakeChatGateway : RemoteChatGateway {
    var snapshotCalls = 0

    override suspend fun snapshot(): ChatSnapshot {
        snapshotCalls += 1
        return ChatSnapshot(5, emptyList(), emptySet(), emptyList())
    }

    override suspend fun startConversation(
        recipientId: String,
        firstMessage: String,
    ): StartConversationResult = error("Not used")

    override suspend fun sendMessage(conversationId: String, body: String): SendMessageResult =
        error("Not used")

    override suspend fun blockUser(targetUserId: String): Boolean = error("Not used")

    override suspend fun reportUser(
        targetUserId: String,
        reason: ReportReason,
        details: String,
        conversationId: String?,
    ): ModerationCase = error("Not used")

    override fun realtimeInvalidations(): Flow<Unit> = emptyFlow()
}

private fun onboardingRequiredSnapshot() = AgeVerificationSnapshot(
    accountStatus = "pending",
    verificationStatus = AgeVerificationStatus.NotStarted,
    onboardingComplete = false,
)

private fun activeUnverifiedSnapshot(status: AgeVerificationStatus) = AgeVerificationSnapshot(
    accountStatus = "active",
    verificationStatus = status,
    onboardingComplete = true,
)

private fun activeVerifiedSnapshot() = AgeVerificationSnapshot(
    accountStatus = "active",
    verificationStatus = AgeVerificationStatus.Verified,
    onboardingComplete = true,
    verificationMethod = "document",
    verifiedAt = "2026-07-31T12:00:00Z",
)

private fun syntheticSharedAlbum() = SharedPrivateAlbum(
    albumId = "00000000-0000-4000-8000-000000000261",
    ownerId = "00000000-0000-4000-8000-000000000262",
    ownerDisplayName = "Pessoa destino",
    itemCount = 1,
    grantedAt = "2026-07-31T12:00:00Z",
)

private fun testProfile(
    displayName: String = "Pessoa Teste",
    bio: String = "Bio sintética",
    intent: String = "Conhecer pessoas",
) = RemoteProfile(
    id = "user-test",
    displayName = displayName,
    age = 31,
    bio = bio,
    intent = intent,
    regionCode = "br-sao-paulo",
    verified = false,
)
