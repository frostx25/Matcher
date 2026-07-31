package com.matcher.app.ui

import com.matcher.app.data.remote.AgeVerificationGateway
import com.matcher.app.data.remote.AgeVerificationSession
import com.matcher.app.data.remote.AgeVerificationSnapshot
import com.matcher.app.data.remote.AgeVerificationStatus
import com.matcher.app.data.remote.AuthGateway
import com.matcher.app.data.remote.CompleteOnboardingRequest
import com.matcher.app.data.remote.CompleteOnboardingResponse
import com.matcher.app.data.remote.DiscoveryPage
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

        viewModel.requestOtp("  pessoa@matcher.invalid ")
        advanceUntilIdle()

        assertEquals("pessoa@matcher.invalid", auth.requestedEmail)
        assertEquals("pessoa@matcher.invalid", viewModel.uiState.value.otpRequestedFor)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun validOtpIsDelegatedWithoutBeingStoredInUiState() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)

        viewModel.verifyOtp("pessoa@matcher.invalid", "123456")
        advanceUntilIdle()

        assertEquals("pessoa@matcher.invalid", auth.verifiedEmail)
        assertEquals("123456", auth.verifiedOtp)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.otpRequestedFor)
    }

    @Test
    fun malformedOtpIsRejectedBeforeCallingAuthProvider() = runTest(dispatcher) {
        val auth = FakeAuthGateway()
        val viewModel = viewModel(auth = auth)

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

        viewModel.uploadPrivateAlbumPhoto(jpeg)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.privateAlbum.myAlbum)
        assertEquals(1, viewModel.uiState.value.privateAlbum.myItems.size)
        assertEquals("available", viewModel.uiState.value.privateAlbum.myItems.single().itemStatus)
        assertTrue(viewModel.uiState.value.privateAlbum.visibleBytes.values.single().contentEquals(jpeg))
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
}

private class FakeAuthGateway(
    initialSession: MatcherSession = MatcherSession.SignedOut,
) : AuthGateway {
    override val session = MutableStateFlow(initialSession)
    var requestedEmail: String? = null
    var verifiedEmail: String? = null
    var verifiedOtp: String? = null

    override suspend fun requestEmailOtp(email: String) {
        requestedEmail = email
    }

    override suspend fun verifyEmailOtp(email: String, token: String) {
        verifiedEmail = email
        verifiedOtp = token
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
        return DiscoveryPage(emptyList(), null)
    }

    override suspend fun getGenderSettings(): GenderSettings = genderSettings

    override suspend fun updateGenderSettings(request: UpdateGenderSettingsRequest): GenderSettings {
        genderSettings = GenderSettings(
            genderIdentityIds = request.genderIdentityIds,
            genderSelfDescription = request.genderSelfDescription,
            genderVisible = request.genderVisible,
            lookingForGenderIds = request.lookingForGenderIds,
            preferenceCursorVersion = genderSettings.preferenceCursorVersion + 1,
        )
        return genderSettings
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
}

private class FakePrivateAlbumGateway : PrivateAlbumGateway {
    var album: PrivateAlbum? = null
    var items = mutableListOf<PrivateAlbumItem>()
    var grants = mutableListOf<PrivateAlbumGrant>()
    var sharedWithMe = mutableListOf<SharedPrivateAlbum>()
    var receivedContent: PrivateAlbumContent? = null
    val bytesByItemId = mutableMapOf<String, ByteArray>()
    var reportedOwnerId: String? = null

    override suspend fun createPrivateAlbum(
        contentPolicyVersion: String,
        contentPolicyAccepted: Boolean,
    ): PrivateAlbum {
        check(contentPolicyAccepted)
        return album ?: PrivateAlbum("10000000-0000-4000-8000-000000000001", "active", 0).also {
            album = it
        }
    }

    override suspend fun getMyPrivateAlbum(): PrivateAlbum? = album?.copy(itemCount = items.size)

    override suspend fun getMyPrivateAlbumItems(): List<PrivateAlbumItem> = items.toList()

    override suspend fun getPrivateAlbum(ownerId: String): PrivateAlbumContent? = receivedContent

    override suspend fun getMyPrivateAlbumGrants(): List<PrivateAlbumGrant> = grants.toList()

    override suspend fun listPrivateAlbumsSharedWithMe(): List<SharedPrivateAlbum> = sharedWithMe.toList()

    override suspend fun uploadPrivateAlbumImage(jpegBytes: ByteArray): PrivateAlbumItem {
        val item = PrivateAlbumItem(
            itemId = "20000000-0000-4000-8000-${(items.size + 1).toString().padStart(12, '0')}",
            position = items.size,
        )
        items += item
        bytesByItemId[item.itemId] = jpegBytes.copyOf()
        album = requireNotNull(album).copy(itemCount = items.size)
        return item
    }

    override suspend fun downloadPrivateAlbumImage(itemId: String): ByteArray =
        requireNotNull(bytesByItemId[itemId]).copyOf()

    override suspend fun grantPrivateAlbumAccess(recipientId: String): Boolean {
        grants.removeAll { it.recipientId == recipientId }
        grants += PrivateAlbumGrant(recipientId, "Pessoa destino", "2026-07-31T12:00:00Z")
        return true
    }

    override suspend fun revokePrivateAlbumAccess(recipientId: String): Boolean =
        grants.removeAll { it.recipientId == recipientId }

    override suspend fun deletePrivateAlbumImage(itemId: String): Boolean {
        bytesByItemId.remove(itemId)
        return items.removeAll { it.itemId == itemId }
    }

    override suspend fun deletePrivateAlbum(): Boolean {
        album = null
        items.clear()
        grants.clear()
        bytesByItemId.clear()
        return true
    }

    override suspend fun reportPrivateAlbum(
        ownerId: String,
        reason: PrivateAlbumReportReason,
        details: String,
        itemId: String?,
    ): String {
        reportedOwnerId = ownerId
        sharedWithMe.removeAll { it.ownerId == ownerId }
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
