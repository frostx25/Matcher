package com.matcher.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.matcher.app.domain.chat.ChatMessage
import com.matcher.app.domain.chat.ChatRepository
import com.matcher.app.domain.chat.Conversation
import com.matcher.app.domain.chat.InMemoryChatRepository
import com.matcher.app.domain.chat.ReportReason
import com.matcher.app.domain.profile.LocalProfileStore
import com.matcher.app.data.remote.SupabaseBackend

internal val Black = Color(0xFF0B0B0F)
internal val Surface = Color(0xFF17131A)
internal val SurfaceRaised = Color(0xFF251E27)
internal val Pink = Color(0xFFFF2D7A)
internal val SoftPink = Color(0xFFFF78A8)
internal val TextSecondary = Color(0xFFB9AEB5)
internal val ActiveMint = Color(0xFF69D6B7)
internal const val DemoUserId = "user-free"

internal data class DemoProfile(
    val id: String,
    val name: String,
    val age: Int,
    val distance: String,
    val intent: String,
    val bio: String,
    val tags: List<String>,
    val initials: String,
    val colors: List<Color>,
    val verified: Boolean = false,
    val avatarUrl: String? = null,
)

internal val demoProfiles = listOf(
    DemoProfile("maya", "Maya", 28, "perto de você", "Conhecer pessoas", "Café sem pressa, música ao vivo e conversas que fogem do óbvio.", listOf("Shows", "Café", "Monogamia"), "MA", listOf(Color(0xFF6A163A), Color(0xFFEA5A8C)), true),
    DemoProfile("leo", "Leo", 31, "na região", "Conversa leve", "Gosto de cozinhar para amigos e descobrir lugares novos pela cidade.", listOf("Cozinha", "Viagens", "Amizade"), "LE", listOf(Color(0xFF251A59), Color(0xFF7367D9))),
    DemoProfile("noah", "Noah", 26, "na região", "Namoro", "Design, cinema e caminhadas longas quando o dia pede menos tela.", listOf("Cinema", "Design", "Namoro"), "NO", listOf(Color(0xFF164E4A), Color(0xFF55C4B6)), true),
    DemoProfile("bia", "Bia", 30, "mais distante", "Amizade", "Leitora de ficção, aprendiz de cerâmica e mãe de duas plantas dramáticas.", listOf("Livros", "Cerâmica", "Amizade"), "BI", listOf(Color(0xFF573519), Color(0xFFD9904D))),
    DemoProfile("alex", "Alex", 29, "perto de você", "Conhecer pessoas", "Fotografia de rua, trilhas curtas e playlists excessivamente organizadas.", listOf("Fotos", "Trilhas", "Não monogamia"), "AL", listOf(Color(0xFF3C245A), Color(0xFFB56BE0))),
    DemoProfile("sam", "Sam", 34, "na região", "Conversa leve", "Entre pedaladas e receitas improvisadas, sempre cabe uma boa conversa.", listOf("Bike", "Cozinha", "Conversa"), "SA", listOf(Color(0xFF173D63), Color(0xFF4EA6DB))),
)

@Composable
fun MatcherApp(
    chatRepository: ChatRepository? = null,
    useRemoteBackend: Boolean = false,
    ageVerificationReturnSignal: Int = 0,
) {
    if (useRemoteBackend) {
        if (SupabaseBackend.isConfigured) {
            RemoteMatcherApp(ageVerificationReturnSignal = ageVerificationReturnSignal)
        } else {
            RemoteConfigurationErrorScreen()
        }
        return
    }

    val context = LocalContext.current
    val profileStore = remember(context) { LocalProfileStore(context) }
    var localProfile by remember { mutableStateOf(profileStore.load()) }
    val savedProfile = localProfile
    if (savedProfile == null) {
        OnboardingScreen(
            onComplete = { profile ->
                profileStore.save(profile)
                localProfile = profile
            },
        )
        return
    }

    val repository = chatRepository ?: remember {
        val conversationId = "conversation-sam-user-free"
        InMemoryChatRepository(
            initialQuota = 5,
            initialConversations = listOf(
                Conversation(
                    id = conversationId,
                    participantIds = setOf("sam", DemoUserId),
                    startedByUserId = "sam",
                    messages = listOf(
                        ChatMessage(
                            id = "message-seed-sam",
                            conversationId = conversationId,
                            senderId = "sam",
                            body = "Oi! Curti sua bio. Quer conversar?",
                        ),
                    ),
                ),
            ),
        )
    }
    val matcherViewModel: MatcherViewModel = viewModel(
        factory = MatcherViewModel.Factory(repository, DemoUserId),
    )
    val uiState = matcherViewModel.uiState

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var selectedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var chatProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeConversationId by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedProfile = demoProfiles.firstOrNull { it.id == selectedProfileId }
    val chatProfile = demoProfiles.firstOrNull { it.id == chatProfileId }
    val activeConversation = uiState.chat.conversations.firstOrNull { it.id == activeConversationId }

    BackHandler(
        enabled = chatProfileId == null && (selectedProfileId != null || activeConversationId != null),
    ) {
        when {
            selectedProfileId != null -> selectedProfileId = null
            activeConversationId != null -> activeConversationId = null
        }
    }

    when {
        selectedProfile != null -> ProfileDetailScreen(
            profile = selectedProfile,
            remainingChats = uiState.chat.remainingQuota,
            onBack = { selectedProfileId = null },
            onStartChat = {
                matcherViewModel.clearError()
                val existingConversation = uiState.chat.conversations.firstOrNull {
                    selectedProfile.id in it.participantIds
                }
                if (existingConversation == null) {
                    chatProfileId = selectedProfile.id
                } else {
                    selectedProfileId = null
                    activeConversationId = existingConversation.id
                }
            },
            onBlock = {
                if (matcherViewModel.blockUser(selectedProfile.id)) selectedProfileId = null
            },
            onReport = { reason, details ->
                if (matcherViewModel.reportUser(selectedProfile.id, reason, details)) selectedProfileId = null
            },
        )

        activeConversation != null -> ConversationDetailScreen(
            currentUserId = DemoUserId,
            conversation = activeConversation,
            profile = demoProfiles.firstOrNull { it.id in activeConversation.participantIds },
            errorMessage = uiState.errorMessage,
            onBack = { activeConversationId = null },
            onSendMessage = { message -> matcherViewModel.sendMessage(activeConversation.id, message) },
            onBlock = { targetId ->
                if (matcherViewModel.blockUser(targetId)) activeConversationId = null
            },
            onReport = { targetId, reason, details ->
                if (matcherViewModel.reportUser(targetId, reason, details)) activeConversationId = null
            },
        )

        else -> Scaffold(
            containerColor = Black,
            bottomBar = {
                BottomNavigationBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            },
        ) { padding ->
            when (selectedTab) {
                0 -> DiscoveryScreen(
                    remainingChats = uiState.chat.remainingQuota,
                    blockedUserIds = uiState.chat.blockedUserIds,
                    onOpenProfile = { selectedProfileId = it },
                    modifier = Modifier.padding(padding),
                )

                1 -> ConversationsScreen(
                    currentUserId = DemoUserId,
                    profiles = demoProfiles,
                    conversations = uiState.chat.conversations,
                    remainingChats = uiState.chat.remainingQuota,
                    onOpenConversation = { activeConversationId = it },
                    modifier = Modifier.padding(padding),
                )

                else -> ProfileScreen(profile = savedProfile, modifier = Modifier.padding(padding))
            }
        }
    }

    chatProfile?.let { profile ->
        StartConversationDialog(
            profile = profile,
            remainingChats = uiState.chat.remainingQuota,
            errorMessage = uiState.errorMessage,
            onDismiss = { chatProfileId = null },
            onSend = { message ->
                matcherViewModel.startConversation(profile.id, message)?.let { conversationId ->
                    chatProfileId = null
                    selectedProfileId = null
                    selectedTab = 1
                    activeConversationId = conversationId
                }
            },
        )
    }
}

@Composable
private fun DiscoveryScreen(
    remainingChats: Int,
    blockedUserIds: Set<String>,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleProfiles = demoProfiles.filterNot { it.id in blockedUserIds }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .testTag("discovery-grid"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header(remainingChats = remainingChats) }
        item(span = { GridItemSpan(maxLineSpan) }) { Filters() }
        items(visibleProfiles, key = { it.id }) { profile ->
            ProfileCard(profile = profile, onOpenProfile = { onOpenProfile(profile.id) })
        }
    }
}

@Composable
private fun Header(remainingChats: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Matcher",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 31.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(4.dp))
            Text("Conexões perto de você", color = TextSecondary, fontSize = 14.sp)
        }
        QuotaPill(remainingChats = remainingChats)
    }
}

@Composable
internal fun QuotaPill(remainingChats: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF291722))
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("chat-quota"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Pink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$remainingChats chats",
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun Filters() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = {},
            label = { Text("Todos") },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Pink,
                labelColor = Black,
                leadingIconContentColor = Black,
            ),
        )
        AssistChip(onClick = {}, label = { Text("Online") }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {}, label = { Text("Verificados") }, leadingIcon = { Icon(Icons.Outlined.Verified, null, Modifier.size(16.dp)) })
        AssistChip(onClick = {}, label = { Text("Filtros") }, leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) })
    }
}

@Composable
private fun ProfileCard(profile: DemoProfile, onOpenProfile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(224.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(profile.colors))
            .clickable(onClick = onOpenProfile)
            .testTag("profile-${profile.id}"),
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
                text = profile.initials,
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))))
                .padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${profile.name}, ${profile.age}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (profile.verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.Verified, "Verificado", tint = Pink, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(profile.distance, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
            Text(profile.intent, color = Color.White.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun ProfileDetailScreen(
    profile: DemoProfile,
    remainingChats: Int,
    onBack: () -> Unit,
    onStartChat: () -> Unit,
    onBlock: () -> Unit,
    onReport: (ReportReason, String) -> Unit,
    receivedPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumShared: Boolean = false,
    onOpenPrivateAlbum: () -> Unit = {},
    onTogglePrivateAlbumShare: () -> Unit = {},
) {
    var showBlockConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .testTag("profile-detail-${profile.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .background(Brush.linearGradient(profile.colors)),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(14.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .zIndex(1f)
                    .testTag("back-profile"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar", tint = Color.White)
            }
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
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${profile.name}, ${profile.age}", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
                if (profile.verified) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Verified, "Perfil verificado", tint = Pink)
                }
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(Icons.Outlined.LocationOn, profile.distance)
                InfoPill(Icons.Outlined.ChatBubbleOutline, profile.intent)
            }
            Text(profile.bio, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, lineHeight = 23.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profile.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceRaised)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(tag, color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF201820))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Distância com privacidade", color = SoftPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("O Matcher mostra apenas uma faixa aproximada. Sua localização exata nunca aparece aqui.", color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Button(
                onClick = onStartChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("start-chat-${profile.id}"),
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, null)
                Spacer(Modifier.width(8.dp))
                Text("Conversar", fontWeight = FontWeight.Bold)
            }
            Text(
                "$remainingChats novas conversas disponíveis. A primeira mensagem abre o chat.",
                modifier = Modifier.fillMaxWidth(),
                color = TextSecondary,
                fontSize = 12.sp,
            )
            if (receivedPrivateAlbumAvailable || myPrivateAlbumAvailable) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF201820))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Álbuns privados",
                        color = SoftPink,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "O acesso é individual. Quem recebe ainda precisa abrir o conteúdo de forma explícita.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    if (receivedPrivateAlbumAvailable) {
                        Button(
                            onClick = onOpenPrivateAlbum,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open-private-album-${profile.id}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoftPink,
                                contentColor = Black,
                            ),
                        ) {
                            Text("Abrir álbum que recebi", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (myPrivateAlbumAvailable) {
                        OutlinedButton(
                            onClick = onTogglePrivateAlbumShare,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("toggle-private-album-${profile.id}"),
                        ) {
                            Text(
                                if (myPrivateAlbumShared) {
                                    "Revogar meu álbum"
                                } else {
                                    "Liberar meu álbum"
                                },
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showBlockConfirmation = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Block, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bloquear")
                }
                OutlinedButton(onClick = { showReportDialog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Flag, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Denunciar")
                }
            }
        }
    }

    if (showBlockConfirmation) {
        BlockConfirmationDialog(
            profileName = profile.name,
            onDismiss = { showBlockConfirmation = false },
            onConfirm = {
                showBlockConfirmation = false
                onBlock()
            },
        )
    }
    if (showReportDialog) {
        ReportDialog(
            profileName = profile.name,
            onDismiss = { showReportDialog = false },
            onConfirm = { reason, details ->
                showReportDialog = false
                onReport(reason, details)
            },
        )
    }
}

@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Surface)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Pink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
internal fun StartConversationDialog(
    profile: DemoProfile,
    remainingChats: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var message by rememberSaveable(profile.id) { mutableStateOf("") }
    val canSend = remainingChats > 0 && message.isNotBlank()
    val quotaMessage = errorMessage ?: if (remainingChats == 0) {
        "Seu limite de novas conversas foi atingido. Conversas existentes continuam liberadas."
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Conversar com ${profile.name}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("A primeira mensagem abre a conversa imediatamente. ${profile.name} poderá responder, bloquear ou denunciar.", color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat-message-input"),
                    placeholder = { Text("Escreva algo gentil...") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(Modifier.height(8.dp))
                Text("$remainingChats novas conversas restantes.", color = TextSecondary, fontSize = 12.sp)
                quotaMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Pink, fontSize = 12.sp, modifier = Modifier.testTag("chat-error"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(message) },
                enabled = canSend,
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                modifier = Modifier.testTag("send-first-message"),
            ) { Text("Enviar e abrir conversa") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun BottomNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    BottomAppBar(containerColor = Surface) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Outlined.Search, "Descobrir") },
            label = { Text("Descobrir") },
            modifier = Modifier.testTag("tab-discovery"),
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Outlined.ChatBubbleOutline, "Conversas") },
            label = { Text("Conversas") },
            modifier = Modifier.testTag("tab-conversations"),
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(Icons.Outlined.Person, "Perfil") },
            label = { Text("Perfil") },
            modifier = Modifier.testTag("tab-profile"),
        )
    }
}
