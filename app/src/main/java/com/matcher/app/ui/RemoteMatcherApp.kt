package com.matcher.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.matcher.app.data.profile.ProfilePhotoProcessor
import com.matcher.app.data.remote.MatcherSession
import com.matcher.app.data.remote.GenderSettings
import com.matcher.app.data.remote.RemoteProfile
import com.matcher.app.data.remote.SupabaseAuthGateway
import com.matcher.app.data.remote.SupabaseAgeVerificationGateway
import com.matcher.app.data.remote.SupabaseBackend
import com.matcher.app.data.remote.SupabaseChatGateway
import com.matcher.app.data.remote.SupabaseProfileGateway
import com.matcher.app.data.remote.SupabasePrivateAlbumGateway
import java.time.Year
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RemoteMatcherApp(ageVerificationReturnSignal: Int = 0) {
    val context = LocalContext.current
    val client = remember { SupabaseBackend.client }
    val remoteViewModel: RemoteMatcherViewModel = viewModel(
        factory = RemoteMatcherViewModel.Factory(
            authGateway = remember(client) { SupabaseAuthGateway(client) },
            profileGateway = remember(client) { SupabaseProfileGateway(client) },
            chatGateway = remember(client) { SupabaseChatGateway(client) },
            privateAlbumGateway = remember(client) { SupabasePrivateAlbumGateway(client) },
            ageVerificationGateway = remember(client) { SupabaseAgeVerificationGateway(client) },
        ),
    )
    val state by remoteViewModel.uiState.collectAsState()

    LaunchedEffect(remoteViewModel, context) {
        remoteViewModel.effects.collect { effect ->
            when (effect) {
                is RemoteMatcherEffect.OpenAgeVerification -> {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                        .launchUrl(context, effect.url.toUri())
                }
            }
        }
    }

    LaunchedEffect(ageVerificationReturnSignal, state.session) {
        if (state.session is MatcherSession.SignedIn) {
            remoteViewModel.onAgeVerificationReturn(ageVerificationReturnSignal)
        }
    }

    when (val session = state.session) {
        MatcherSession.Loading -> RemoteLoadingScreen()
        MatcherSession.SignedOut,
        MatcherSession.RefreshFailed -> RemoteAuthScreen(
            otpRequestedFor = state.otpRequestedFor,
            otpDeliveryStatus = state.otpDeliveryStatus,
            otpChallengeGeneration = state.otpChallengeGeneration,
            otpResendSecondsRemaining = state.otpResendSecondsRemaining,
            loading = state.loading,
            errorMessage = state.errorMessage,
            onRequestOtp = remoteViewModel::requestOtp,
            onVerifyOtp = remoteViewModel::verifyOtp,
            onChangeEmail = remoteViewModel::changeOtpEmail,
        )
        is MatcherSession.SignedIn -> when (state.signedInStage) {
            SignedInStage.Resolving -> RemoteLoadingScreen()
            SignedInStage.Onboarding -> RemoteOnboardingScreen(
                loading = state.loading,
                errorMessage = state.errorMessage,
                onComplete = remoteViewModel::completeOnboarding,
            )
            SignedInStage.Active -> if (state.ageVerificationOpen) {
                AgeVerificationScreen(
                    status = state.ageVerificationStatus,
                    consentGranted = state.ageVerificationConsentGranted,
                    loading = state.verificationLoading || state.loading,
                    errorMessage = state.errorMessage,
                    onConsentChanged = remoteViewModel::setAgeVerificationConsent,
                    onStartVerification = remoteViewModel::startAgeVerification,
                    onRefreshStatus = remoteViewModel::refreshAgeVerificationStatus,
                    onBack = remoteViewModel::closeAgeVerification,
                )
            } else {
                state.profile?.let { profile ->
                    RemoteHome(
                        currentUserId = session.userId,
                        profile = profile,
                        state = state,
                        viewModel = remoteViewModel,
                    )
                } ?: RemoteAccessUnavailableScreen(
                    message = "Não foi possível carregar o perfil ativo.",
                    loading = state.loading,
                    onRetry = remoteViewModel::refreshAgeVerificationStatus,
                    onSignOut = remoteViewModel::signOut,
                )
            }
            SignedInStage.Unavailable -> RemoteAccessUnavailableScreen(
                message = state.errorMessage,
                loading = state.loading || state.verificationLoading,
                onRetry = remoteViewModel::refreshAgeVerificationStatus,
                onSignOut = remoteViewModel::signOut,
            )
        }
    }
}

@Composable
internal fun RemoteAuthScreen(
    otpRequestedFor: String?,
    otpDeliveryStatus: OtpDeliveryStatus?,
    otpChallengeGeneration: Long,
    otpResendSecondsRemaining: Int,
    loading: Boolean,
    errorMessage: String?,
    onRequestOtp: (String) -> Unit,
    onVerifyOtp: (String, String) -> Unit,
    onChangeEmail: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(otpRequestedFor.orEmpty()) }
    var otp by remember(otpRequestedFor, otpChallengeGeneration) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(horizontal = 24.dp, vertical = 48.dp)
            .testTag("remote-auth-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Matcher", color = MaterialTheme.colorScheme.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("Entre por e-mail para usar o backend de desenvolvimento.", color = TextSecondary)
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().testTag("remote-email-input"),
            label = { Text("E-mail") },
            enabled = otpRequestedFor == null && !loading,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        if (otpRequestedFor == null) {
            Button(
                onClick = { onRequestOtp(email) },
                enabled = !loading && email.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("request-email-otp"),
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
            ) { Text("Enviar código", fontWeight = FontWeight.Bold) }
        } else {
            Text(
                when (otpDeliveryStatus) {
                    OtpDeliveryStatus.Confirmed ->
                        "Enviamos um código de $EMAIL_OTP_LENGTH dígitos para $otpRequestedFor. Consulte o e-mail em qualquer aparelho e digite o código abaixo."
                    OtpDeliveryStatus.Indeterminate ->
                        "O envio para $otpRequestedFor ainda não foi confirmado. Se o código chegar, digite-o abaixo antes de solicitar outro."
                    OtpDeliveryStatus.RateLimited ->
                        "Já houve uma solicitação recente para $otpRequestedFor. Digite o código mais recente que recebeu ou aguarde para reenviar."
                    null ->
                        "Digite abaixo o código de $EMAIL_OTP_LENGTH dígitos recebido por e-mail."
                },
                color = TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedTextField(
                value = otp,
                onValueChange = { value ->
                    val normalized = value.filter(Char::isDigit).take(EMAIL_OTP_LENGTH)
                    val completedNow = normalized.length == EMAIL_OTP_LENGTH && otp.length < EMAIL_OTP_LENGTH
                    otp = normalized
                    if (completedNow && !loading) onVerifyOtp(otpRequestedFor, normalized)
                },
                modifier = Modifier.fillMaxWidth().testTag("remote-otp-input"),
                label = { Text("Código de $EMAIL_OTP_LENGTH dígitos") },
                enabled = !loading,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            Text(
                if (loading) "Validando código…" else "A validação começa automaticamente no sexto dígito.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.testTag("otp-auto-validation"),
            )
            OutlinedButton(
                onClick = { onRequestOtp(otpRequestedFor) },
                enabled = !loading && otpResendSecondsRemaining == 0,
                modifier = Modifier.fillMaxWidth().testTag("resend-email-otp"),
            ) {
                Text(
                    if (otpResendSecondsRemaining > 0) {
                        "Reenviar em ${otpResendSecondsRemaining}s"
                    } else {
                        "Reenviar código"
                    },
                )
            }
            OutlinedButton(
                onClick = onChangeEmail,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().testTag("change-otp-email"),
            ) { Text("Usar outro e-mail") }
        }
        if (loading) CircularProgressIndicator(color = Pink, modifier = Modifier.align(Alignment.CenterHorizontally))
        errorMessage?.let { Text(it, color = Pink, modifier = Modifier.testTag("remote-auth-error")) }
        Text("Ambiente de desenvolvimento. Não use dados sensíveis ou de terceiros.", color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun RemoteOnboardingScreen(
    loading: Boolean,
    errorMessage: String?,
    onComplete: (
        String,
        Int,
        String,
        String,
        Set<String>,
        String,
        Boolean,
        Set<String>,
        Boolean,
    ) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var birthYearInput by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var intent by rememberSaveable { mutableStateOf("Conhecer pessoas") }
    var genderIdentityIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var genderSelfDescription by rememberSaveable { mutableStateOf("") }
    var genderVisible by rememberSaveable { mutableStateOf(true) }
    var lookingForGenderIds by rememberSaveable { mutableStateOf(setOf("everyone")) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    val currentYear = remember { Year.now().value }
    val birthYear = birthYearInput.toIntOrNull()
    val approximateAge = birthYear?.let { currentYear - it }
    val locallyAdult = approximateAge?.let { it in 18..120 } == true
    val identityValid = genderIdentityIds.isNotEmpty() &&
        ("self_described" !in genderIdentityIds || genderSelfDescription.isNotBlank())
    val preferenceValid = lookingForGenderIds.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Black).testTag("remote-onboarding-screen"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Complete seu perfil", color = MaterialTheme.colorScheme.onBackground, fontSize = 30.sp, fontWeight = FontWeight.Black) }
        item { Text("Você entra como não verificado e pode solicitar o selo 18+ depois no Perfil.", color = TextSecondary) }
        item {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("remote-name-input"), label = { Text("Nome de exibição") })
        }
        item {
            OutlinedTextField(
                birthYearInput,
                { value -> birthYearInput = value.filter(Char::isDigit).take(4) },
                Modifier.fillMaxWidth().testTag("remote-birth-year-input"),
                label = { Text("Ano de nascimento") },
                placeholder = { Text("AAAA") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        if (birthYearInput.length == 4 && !locallyAdult) {
            item { Text("Informe um ano válido de uma pessoa com 18 anos ou mais.", color = Pink) }
        }
        item {
            GenderIdentitySelector(
                selected = genderIdentityIds,
                selfDescription = genderSelfDescription,
                visible = genderVisible,
                enabled = !loading,
                onSelectedChange = { selected ->
                    genderIdentityIds = selected
                    if ("prefer_not_to_say" in selected) genderVisible = false
                    if ("self_described" !in selected) genderSelfDescription = ""
                },
                onSelfDescriptionChange = { genderSelfDescription = it },
                onVisibleChange = { genderVisible = it },
            )
        }
        item {
            LookingForGenderSelector(
                selected = lookingForGenderIds,
                enabled = !loading,
                onSelectedChange = { lookingForGenderIds = it },
            )
        }
        item { OutlinedTextField(bio, { bio = it }, Modifier.fillMaxWidth(), label = { Text("Bio (opcional)") }, minLines = 3) }
        item { OutlinedTextField(intent, { intent = it }, Modifier.fillMaxWidth(), label = { Text("O que você busca?") }) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { accepted = !accepted }.testTag("remote-terms-confirmation"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Checkbox(accepted, { accepted = it })
                Text("Confirmo 18+ e aceito os Termos e a Política de Privacidade.", color = TextSecondary, fontSize = 13.sp)
            }
        }
        item {
            Button(
                onClick = {
                    birthYear?.let {
                        onComplete(
                            name,
                            it,
                            bio,
                            intent,
                            genderIdentityIds,
                            genderSelfDescription,
                            genderVisible,
                            lookingForGenderIds,
                            accepted,
                        )
                    }
                },
                enabled = !loading && name.isNotBlank() && locallyAdult &&
                    identityValid && preferenceValid && accepted,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("remote-complete-onboarding"),
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
            ) { Text("Criar perfil", fontWeight = FontWeight.Bold) }
        }
        if (loading) item { CircularProgressIndicator(color = Pink) }
        errorMessage?.let { item { Text(it, color = Pink) } }
    }
}

@Composable
private fun RemoteHome(
    currentUserId: String,
    profile: RemoteProfile,
    state: RemoteMatcherUiState,
    viewModel: RemoteMatcherViewModel,
) {
    val remoteProfiles = state.discovery.profiles.map(RemoteProfile::toDemoProfile)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var firstMessageProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeConversationId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedProfile = remoteProfiles.firstOrNull { it.id == selectedProfileId }
    val firstMessageProfile = remoteProfiles.firstOrNull { it.id == firstMessageProfileId }
    val activeConversation = state.chat.conversations.firstOrNull { it.id == activeConversationId }

    LaunchedEffect(selectedProfileId) {
        if (selectedProfileId != null) viewModel.refreshPrivateAlbumAccess()
    }

    LaunchedEffect(state.privateAlbum.destination) {
        if (state.privateAlbum.destination is PrivateAlbumDestination.Received) {
            while (true) {
                delay(5_000)
                viewModel.revalidateReceivedPrivateAlbum()
            }
        }
    }

    BackHandler(
        state.privateAlbum.destination != null || selectedProfile != null || activeConversation != null,
    ) {
        when {
            state.privateAlbum.destination != null -> viewModel.closePrivateAlbum()
            selectedProfile != null -> selectedProfileId = null
            activeConversation != null -> activeConversationId = null
        }
    }

    when {
        state.privateAlbum.destination == PrivateAlbumDestination.Mine -> {
            val grantIds = state.privateAlbum.myGrants.mapTo(mutableSetOf()) { it.recipientId }
            val albumTargets = buildMap {
                state.privateAlbum.knownRecipients.forEach { (id, name) -> put(id, name) }
                state.discovery.profiles.forEach { remote -> put(remote.id, remote.displayName) }
            }.filterKeys { it != currentUserId }
            MyPrivateAlbumScreen(
                albumExists = state.privateAlbum.myAlbum != null,
                photos = state.privateAlbum.visibleItems.mapNotNull { item ->
                    state.privateAlbum.visibleBytes[item.itemId]?.let { bytes ->
                        PrivateAlbumPhotoUi(item.itemId, item.position, bytes)
                    }
                },
                grants = state.privateAlbum.myGrants.map {
                    PrivateAlbumGrantUi(it.recipientId, it.displayName)
                },
                targets = albumTargets.map { (id, displayName) ->
                    PrivateAlbumTargetUi(
                        id = id,
                        displayName = displayName,
                        shared = id in grantIds,
                    )
                }.sortedBy { it.displayName.lowercase() },
                loading = state.privateAlbum.loading,
                errorMessage = state.errorMessage,
                onBack = viewModel::closePrivateAlbum,
                onAddPhoto = viewModel::uploadPrivateAlbumPhoto,
                onPhotoError = viewModel::reportProfilePhotoPreparationFailure,
                onDeletePhoto = viewModel::deletePrivateAlbumPhoto,
                onToggleGrant = viewModel::togglePrivateAlbumGrant,
                onRevokeGrants = viewModel::revokePrivateAlbumGrants,
                onDeleteAlbum = viewModel::deleteMyPrivateAlbum,
            )
        }
        state.privateAlbum.destination is PrivateAlbumDestination.Warning -> {
            val destination = state.privateAlbum.destination
            PrivateAlbumWarningScreen(
                ownerName = destination.ownerName,
                loading = state.privateAlbum.loading,
                errorMessage = state.errorMessage,
                onBack = viewModel::closePrivateAlbum,
                onReveal = viewModel::revealReceivedPrivateAlbum,
            )
        }
        state.privateAlbum.destination is PrivateAlbumDestination.Received -> {
            val destination = state.privateAlbum.destination
            ReceivedPrivateAlbumScreen(
                ownerName = destination.ownerName,
                photos = state.privateAlbum.visibleItems.mapNotNull { item ->
                    state.privateAlbum.visibleBytes[item.itemId]?.let { bytes ->
                        PrivateAlbumPhotoUi(item.itemId, item.position, bytes)
                    }
                },
                loading = state.privateAlbum.loading,
                errorMessage = state.errorMessage,
                onBack = viewModel::closePrivateAlbum,
                onReport = viewModel::reportPrivateAlbum,
            )
        }
        selectedProfile != null -> ProfileDetailScreen(
            profile = selectedProfile,
            remainingChats = state.chat.remainingQuota,
            onBack = { selectedProfileId = null },
            onStartChat = {
                val existing = state.chat.conversations.firstOrNull { selectedProfile.id in it.participantIds }
                if (existing == null) firstMessageProfileId = selectedProfile.id else activeConversationId = existing.id
            },
            onBlock = { viewModel.blockUser(selectedProfile.id) { selectedProfileId = null } },
            onReport = { reason, details ->
                viewModel.reportUser(selectedProfile.id, reason, details, null) { selectedProfileId = null }
            },
            receivedPrivateAlbumAvailable = state.privateAlbum.sharedWithMe.any {
                it.ownerId == selectedProfile.id
            },
            myPrivateAlbumAvailable = (state.privateAlbum.myAlbum?.itemCount ?: 0) > 0,
            myPrivateAlbumShared = state.privateAlbum.myGrants.any {
                it.recipientId == selectedProfile.id
            },
            onOpenPrivateAlbum = {
                viewModel.openReceivedPrivateAlbum(selectedProfile.id, selectedProfile.name)
            },
            onTogglePrivateAlbumShare = {
                viewModel.togglePrivateAlbumGrant(
                    recipientId = selectedProfile.id,
                    currentlyShared = state.privateAlbum.myGrants.any {
                        it.recipientId == selectedProfile.id
                    },
                )
            },
        )
        activeConversation != null -> ConversationDetailScreen(
            currentUserId = currentUserId,
            conversation = activeConversation,
            profile = remoteProfiles.firstOrNull { it.id in activeConversation.participantIds },
            errorMessage = state.errorMessage,
            onBack = { activeConversationId = null },
            onSendMessage = { viewModel.sendMessage(activeConversation.id, it) },
            onBlock = { target -> viewModel.blockUser(target) { activeConversationId = null } },
            onReport = { target, reason, details ->
                viewModel.reportUser(target, reason, details, activeConversation.id) { activeConversationId = null }
            },
        )
        else -> Scaffold(
            containerColor = Black,
            bottomBar = { BottomNavigationBar(selectedTab, onTabSelected = { selectedTab = it }) },
        ) { padding ->
            when (selectedTab) {
                0 -> RemoteDiscoveryScreen(
                    profiles = remoteProfiles,
                    viewerAvatarUrl = profile.avatarUrl,
                    viewerInitials = profile.displayName.trim().take(2).uppercase(),
                    remainingChats = state.chat.remainingQuota,
                    loading = state.loading,
                    hasMore = state.discovery.nextCursor != null,
                    lookingForGenderIds = state.genderSettings?.lookingForGenderIds?.toSet()
                        ?: setOf("everyone"),
                    onLookingForChange = { lookingFor ->
                        state.genderSettings?.let { settings ->
                            viewModel.updateGenderSettings(
                                genderIdentityIds = settings.genderIdentityIds.toSet(),
                                genderSelfDescription = settings.genderSelfDescription.orEmpty(),
                                genderVisible = settings.genderVisible,
                                lookingForGenderIds = lookingFor,
                            )
                        }
                    },
                    onLoadMore = viewModel::loadMoreDiscovery,
                    onOpenAccount = { selectedTab = 2 },
                    onOpen = { selectedProfileId = it },
                    modifier = Modifier.padding(padding),
                )
                1 -> ConversationsScreen(
                    currentUserId = currentUserId,
                    profiles = remoteProfiles,
                    conversations = state.chat.conversations,
                    remainingChats = state.chat.remainingQuota,
                    onOpenConversation = { activeConversationId = it },
                    modifier = Modifier.padding(padding),
                )
                else -> RemoteProfileScreen(
                    profile = profile,
                    genderSettings = state.genderSettings,
                    verificationStatus = state.ageVerificationStatus,
                    photoLoading = state.photoLoading,
                    errorMessage = state.errorMessage,
                    onOpenVerification = viewModel::openAgeVerification,
                    onPhotoSelected = viewModel::submitProfilePhoto,
                    onPhotoError = viewModel::reportProfilePhotoPreparationFailure,
                    onUpdateGenderSettings = viewModel::updateGenderSettings,
                    onOpenPrivateAlbum = viewModel::openMyPrivateAlbum,
                    privateAlbumItemCount = state.privateAlbum.myAlbum?.itemCount ?: 0,
                    privateAlbumGrantCount = state.privateAlbum.myGrants.size,
                    sharedPrivateAlbums = state.privateAlbum.sharedWithMe.map { shared ->
                        SharedPrivateAlbumUi(
                            ownerId = shared.ownerId,
                            ownerName = shared.ownerDisplayName,
                            itemCount = shared.itemCount,
                        )
                    },
                    onOpenSharedPrivateAlbum = { shared ->
                        viewModel.openReceivedPrivateAlbum(shared.ownerId, shared.ownerName)
                    },
                    onSignOut = viewModel::signOut,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    firstMessageProfile?.let { target ->
        StartConversationDialog(
            profile = target,
            remainingChats = state.chat.remainingQuota,
            errorMessage = state.errorMessage,
            onDismiss = { firstMessageProfileId = null },
            onSend = { message ->
                viewModel.startConversation(target.id, message) { conversationId ->
                    firstMessageProfileId = null
                    selectedProfileId = null
                    selectedTab = 1
                    activeConversationId = conversationId
                }
            },
        )
    }
}

@Composable
internal fun RemoteDiscoveryScreen(
    profiles: List<DemoProfile>,
    viewerAvatarUrl: String?,
    viewerInitials: String,
    remainingChats: Int,
    loading: Boolean,
    hasMore: Boolean,
    lookingForGenderIds: Set<String>,
    onLookingForChange: (Set<String>) -> Unit,
    onLoadMore: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    profileTagPrefix: String = "remote-profile-",
) {
    val density = LocalDensity.current
    val windowWidthDp = (LocalWindowInfo.current.containerSize.width / density.density).toInt()
    val discoveryColumns = discoveryColumnCountForWidth(windowWidthDp)
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var draftLookingFor by remember(lookingForGenderIds, filtersOpen) {
        mutableStateOf(lookingForGenderIds)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(discoveryColumns),
        modifier = modifier
            .fillMaxSize()
            .background(Black)
            .testTag("remote-discovery"),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("remote-discovery-header"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .border(2.dp, Pink, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenAccount)
                        .testTag("open-account-from-discovery"),
                ) {
                    RemoteProfileAvatar(
                        imageUrl = viewerAvatarUrl,
                        initials = viewerInitials,
                        size = 36.dp,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Perto",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.6).sp,
                    )
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(width = 24.dp, height = 3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Pink),
                    )
                    Text("Localização aproximada", color = TextSecondary, fontSize = 11.sp)
                }
                QuotaPill(remainingChats)
                IconButton(
                    onClick = { filtersOpen = !filtersOpen },
                    enabled = !loading,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (filtersOpen) Pink else Surface)
                        .testTag("toggle-gender-filter"),
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = if (filtersOpen) "Fechar filtros" else "Abrir filtros",
                        tint = if (filtersOpen) Black else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("discovery-filter-summary"),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Pink),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mostrar", color = TextSecondary, fontSize = 10.sp)
                    Text(
                        if ("everyone" in lookingForGenderIds) {
                            "Todas as pessoas"
                        } else {
                            genderLabels(lookingForGenderIds)
                        },
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Filtro privado",
                    color = SoftPink,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (filtersOpen) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(SurfaceRaised)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column {
                        Text("Quem aparece aqui", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        Text(
                            "Esta escolha é privada e aplicada pelo servidor.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    LookingForGenderSelector(
                        selected = draftLookingFor,
                        enabled = !loading,
                        onSelectedChange = { draftLookingFor = it },
                        title = "Mostrar perfis de",
                    )
                    Button(
                        onClick = {
                            onLookingForChange(draftLookingFor)
                            filtersOpen = false
                        },
                        enabled = !loading && draftLookingFor.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("save-gender-filter"),
                        colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                    ) { Text("Aplicar filtro", fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Pink) }
            }
        }
        if (profiles.isEmpty() && !loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Ninguém por aqui ainda", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    Text(
                        "Altere os filtros ou volte mais tarde.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        gridItems(profiles, key = { it.id }) { item ->
            RemoteDiscoveryProfileCard(
                profile = item,
                onOpen = { onOpen(item.id) },
                testTagPrefix = profileTagPrefix,
            )
        }
        if (hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 12.dp)
                        .testTag("load-more-discovery"),
                ) { Text("Carregar mais perfis") }
            }
        }
    }
}

internal fun discoveryColumnCountForWidth(widthDp: Int): Int = when {
    widthDp >= 720 -> 6
    widthDp >= 600 -> 5
    widthDp >= 480 -> 4
    else -> 3
}

@Composable
private fun RemoteDiscoveryProfileCard(
    profile: DemoProfile,
    onOpen: () -> Unit,
    testTagPrefix: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.74f)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(profile.colors))
            .clickable(onClick = onOpen)
            .testTag("$testTagPrefix${profile.id}"),
    ) {
        if (profile.avatarUrl != null) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = "Foto de ${profile.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                profile.initials,
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
                    ),
                )
                .padding(start = 7.dp, end = 7.dp, top = 28.dp, bottom = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${profile.name}, ${profile.age}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (profile.verified) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "✓",
                        color = Pink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Text(
                profile.distance,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                profile.intent,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RemoteProfileScreen(
    profile: RemoteProfile,
    genderSettings: GenderSettings?,
    verificationStatus: com.matcher.app.data.remote.AgeVerificationStatus,
    photoLoading: Boolean,
    errorMessage: String?,
    onOpenVerification: () -> Unit,
    onPhotoSelected: (ByteArray) -> Unit,
    onPhotoError: () -> Unit,
    onUpdateGenderSettings: (Set<String>, String, Boolean, Set<String>) -> Unit,
    onOpenPrivateAlbum: () -> Unit,
    privateAlbumItemCount: Int,
    privateAlbumGrantCount: Int,
    sharedPrivateAlbums: List<SharedPrivateAlbumUi>,
    onOpenSharedPrivateAlbum: (SharedPrivateAlbumUi) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editGender by rememberSaveable { mutableStateOf(false) }
    var identityDraft by remember(genderSettings, editGender) {
        mutableStateOf(genderSettings?.genderIdentityIds?.toSet() ?: emptySet())
    }
    var selfDescriptionDraft by remember(genderSettings, editGender) {
        mutableStateOf(genderSettings?.genderSelfDescription.orEmpty())
    }
    var genderVisibleDraft by remember(genderSettings, editGender) {
        mutableStateOf(genderSettings?.genderVisible ?: false)
    }
    var lookingForDraft by remember(genderSettings, editGender) {
        mutableStateOf(genderSettings?.lookingForGenderIds?.toSet() ?: setOf("everyone"))
    }
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val jpeg = withContext(Dispatchers.IO) {
                        ProfilePhotoProcessor.prepareJpeg(context.contentResolver, uri)
                    }
                    onPhotoSelected(jpeg)
                } catch (_: Exception) {
                    onPhotoError()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("remote-profile-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Seu perfil", color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RemoteProfileAvatar(
                imageUrl = profile.avatarUrl,
                initials = profile.displayName.trim().take(2).uppercase(),
                size = 128.dp,
            )
            Text("${profile.displayName}, ${profile.age}", color = MaterialTheme.colorScheme.onBackground, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text(profile.intent, color = Pink)
            Text(profile.bio.ifBlank { "Adicione uma bio para contar mais sobre você." }, color = TextSecondary)
            Text(
                profilePhotoStatusText(
                    status = profile.avatarModerationStatus,
                    hasApprovedPhoto = profile.avatarPath != null,
                ),
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.testTag("profile-photo-status"),
            )
            OutlinedButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !photoLoading,
                modifier = Modifier.fillMaxWidth().testTag("choose-profile-photo"),
            ) {
                Text(
                    if (profile.avatarPath == null && profile.avatarCandidatePath == null) {
                        "Escolher foto"
                    } else {
                        "Trocar foto"
                    },
                )
            }
            if (photoLoading) CircularProgressIndicator(color = Pink)
        }
        Text(
            "Fotos novas ficam privadas durante a análise. Conteúdo adulto ou abusivo fica oculto; sem uma foto já aprovada, aparece o avatar cinza.",
            color = TextSecondary,
            fontSize = 12.sp,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Identidade e descoberta", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Text(
                "Identidade: ${genderLabels(genderSettings?.genderIdentityIds.orEmpty(), genderSettings?.genderVisible != true)}",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Text(
                "Quero encontrar: ${if (genderSettings?.lookingForGenderIds?.contains("everyone") != false) "Todas as pessoas" else genderLabels(genderSettings.lookingForGenderIds)}",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = { editGender = !editGender },
                enabled = genderSettings != null && !photoLoading,
                modifier = Modifier.fillMaxWidth().testTag("edit-gender-settings"),
            ) { Text(if (editGender) "Cancelar edição" else "Editar identidade e preferência") }
            if (editGender) {
                GenderIdentitySelector(
                    selected = identityDraft,
                    selfDescription = selfDescriptionDraft,
                    visible = genderVisibleDraft,
                    enabled = !photoLoading,
                    onSelectedChange = { selected ->
                        identityDraft = selected
                        if ("prefer_not_to_say" in selected) genderVisibleDraft = false
                        if ("self_described" !in selected) selfDescriptionDraft = ""
                    },
                    onSelfDescriptionChange = { selfDescriptionDraft = it },
                    onVisibleChange = { genderVisibleDraft = it },
                )
                LookingForGenderSelector(
                    selected = lookingForDraft,
                    enabled = !photoLoading,
                    onSelectedChange = { lookingForDraft = it },
                )
                Button(
                    onClick = {
                        onUpdateGenderSettings(
                            identityDraft,
                            selfDescriptionDraft,
                            genderVisibleDraft,
                            lookingForDraft,
                        )
                        editGender = false
                    },
                    enabled = identityDraft.isNotEmpty() && lookingForDraft.isNotEmpty() &&
                        ("self_described" !in identityDraft || selfDescriptionDraft.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().testTag("save-gender-settings"),
                    colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                ) { Text("Salvar", fontWeight = FontWeight.Bold) }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF201820))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Meus álbuns", color = SoftPink, fontWeight = FontWeight.Bold)
            Text(
                if (privateAlbumItemCount == 0) {
                    "Crie seu álbum privado e escolha individualmente quem poderá abrir."
                } else {
                    "$privateAlbumItemCount/10 fotos · $privateAlbumGrantCount ${if (privateAlbumGrantCount == 1) "pessoa com acesso" else "pessoas com acesso"}"
                },
                color = TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = onOpenPrivateAlbum,
                modifier = Modifier.fillMaxWidth().testTag("open-my-private-album"),
            ) { Text(if (privateAlbumItemCount == 0) "Criar álbum privado" else "Abrir meus álbuns") }
        }

        SharedPrivateAlbumsSection(
            albums = sharedPrivateAlbums,
            onOpen = onOpenSharedPrivateAlbum,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (verificationStatus == com.matcher.app.data.remote.AgeVerificationStatus.Verified) {
                    "18+ verificado"
                } else {
                    "Perfil não verificado"
                },
                color = if (verificationStatus == com.matcher.app.data.remote.AgeVerificationStatus.Verified) ActiveMint else SoftPink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("profile-verification-status"),
            )
            Text(
                "A verificação com documento é opcional e pode ser feita quando você estiver preparado.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            OutlinedButton(
                onClick = onOpenVerification,
                enabled = verificationStatus != com.matcher.app.data.remote.AgeVerificationStatus.Verified,
                modifier = Modifier.fillMaxWidth().testTag("open-age-verification"),
            ) {
                Text(if (verificationStatus == com.matcher.app.data.remote.AgeVerificationStatus.Verified) "Verificação concluída" else "Verificar identidade e maioridade")
            }
        }
        errorMessage?.let { Text(it, color = Pink, modifier = Modifier.testTag("remote-profile-error")) }
        Text("Região aproximada: ${profile.regionCode}", color = TextSecondary, fontSize = 12.sp)
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sair") }
    }
}

@Composable
private fun RemoteProfileAvatar(
    imageUrl: String?,
    initials: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF49464D)),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Foto do perfil",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                initials.ifBlank { "?" },
                color = Color.White.copy(alpha = 0.88f),
                fontWeight = FontWeight.Black,
            )
        }
    }
}

private fun profilePhotoStatusText(status: String, hasApprovedPhoto: Boolean): String = when (status) {
    "pending" -> "Foto em análise — somente você pode visualizá-la agora."
    "approved" -> "Foto aprovada e visível no perfil."
    "blocked_adult" -> if (hasApprovedPhoto) {
        "Nova foto adulta ocultada. Sua foto aprovada anterior continua visível."
    } else {
        "Foto adulta ocultada. Escolha outra imagem."
    }
    "blocked_abusive" -> if (hasApprovedPhoto) {
        "Nova foto abusiva bloqueada. Sua foto aprovada anterior continua visível."
    } else {
        "Foto abusiva bloqueada. Escolha outra imagem."
    }
    else -> if (hasApprovedPhoto) {
        "Foto aprovada e visível no perfil."
    } else {
        "Nenhuma foto selecionada."
    }
}

@Composable
private fun RemoteLoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Pink)
        Spacer(Modifier.height(12.dp))
        Text("Conectando ao Matcher…", color = TextSecondary)
    }
}

private fun RemoteProfile.toDemoProfile(): DemoProfile {
    val palette = listOf(
        listOf(Color(0xFF6A163A), Color(0xFFEA5A8C)),
        listOf(Color(0xFF251A59), Color(0xFF7367D9)),
        listOf(Color(0xFF164E4A), Color(0xFF55C4B6)),
    )
    val genderTags = if (genderVisible) {
        genderIdentityIds.mapNotNull { id ->
            if (id == "self_described") {
                genderSelfDescription?.takeIf(String::isNotBlank)
            } else {
                IdentityGenderChoices.firstOrNull { it.id == id }?.label
            }
        }
    } else {
        emptyList()
    }
    return DemoProfile(
        id = id,
        name = displayName,
        age = age,
        distance = "na região",
        intent = intent,
        bio = bio,
        tags = genderTags,
        initials = displayName.trim().take(2).uppercase(),
        colors = palette[(id.hashCode() and Int.MAX_VALUE) % palette.size],
        verified = verified,
        avatarUrl = avatarUrl,
    )
}
