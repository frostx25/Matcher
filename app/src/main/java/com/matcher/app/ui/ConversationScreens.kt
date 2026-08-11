package com.matcher.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as MaterialSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.matcher.app.data.profile.ProfilePhotoProcessor
import com.matcher.app.domain.chat.ChatMessage
import com.matcher.app.domain.chat.ChatDeliveryStatus
import com.matcher.app.domain.chat.ChatMediaStatus
import com.matcher.app.domain.chat.ChatMessageKind
import com.matcher.app.domain.chat.Conversation
import com.matcher.app.domain.chat.ReportReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@Composable
internal fun ConversationsScreen(
    currentUserId: String,
    profiles: List<DemoProfile>,
    conversations: List<Conversation>,
    remainingChats: Int,
    onOpenConversation: (String) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val visibleConversations = conversations.filter { it.archived == showArchived }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Black)
            .testTag("conversations-screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                ) {
                    Text(
                        "Conversas",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.6).sp,
                    )
                    Text(
                        "Mensagens diretas, sem match ou solicitação.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
                QuotaPill(remainingChats)
            }
        }

        if (conversations.any { it.archived }) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showArchived, onClick = { showArchived = false }, label = { Text("Ativas") })
                    FilterChip(selected = showArchived, onClick = { showArchived = true }, label = { Text("Arquivadas") }, modifier = Modifier.testTag("show-archived-conversations"))
                }
            }
        }

        if (visibleConversations.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(ActiveMint))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ativas",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "SEM ACEITE PRÉVIO",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }
            }
            items(visibleConversations, key = { it.id }) { conversation ->
                val otherUserId = conversation.participantIds.firstOrNull { it != currentUserId }
                val profile = profiles.firstOrNull { it.id == otherUserId }
                if (profile != null) {
                    ActiveConversationCard(
                        conversation = conversation,
                        profile = profile,
                        currentUserId = currentUserId,
                        onOpen = { onOpenConversation(conversation.id) },
                    )
                }
            }
        }

        if (visibleConversations.isEmpty()) {
            item {
                EmptyConversationCard(onExplore = onExplore)
            }
        }
    }
}

@Composable
private fun ActiveConversationCard(
    conversation: Conversation,
    profile: DemoProfile,
    currentUserId: String,
    onOpen: () -> Unit,
) {
    val lastMessage = conversation.messages.lastOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Surface)
            .clickable(onClick = onOpen)
            .padding(12.dp)
            .testTag("active-${profile.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(profile.colors)),
            contentAlignment = Alignment.Center,
        ) {
            if (profile.avatarUrl != null) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = "Foto pública de ${profile.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    profile.initials,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (profile.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Verified, "18+ verificado", tint = Pink, modifier = Modifier.size(15.dp))
                }
            }
            Text(
                text = when {
                    lastMessage == null -> "Conversa iniciada"
                    lastMessage.kind == ChatMessageKind.Photo && lastMessage.senderId == currentUserId -> "Você: Foto"
                    lastMessage.kind == ChatMessageKind.Photo -> "Foto"
                    lastMessage.senderId == currentUserId -> "Você: ${lastMessage.body}"
                    else -> lastMessage.body
                },
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(ActiveMint))
                Spacer(Modifier.width(6.dp))
                Text("DIRETA", color = ActiveMint, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                Text("  ·  ${profile.distance}", color = TextSecondary, fontSize = 10.sp)
            }
        }
        if (conversation.unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Pink)
                    .testTag("unread-${conversation.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    conversation.unreadCount.coerceAtMost(99).toString(),
                    color = Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        } else {
            Icon(Icons.Outlined.ChevronRight, "Abrir conversa", tint = TextSecondary)
        }
    }
}

@Composable
private fun EmptyConversationCard(onExplore: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Surface)
            .padding(22.dp)
            .testTag("empty-conversations"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Color(0xFF351525)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Pink, modifier = Modifier.size(27.dp))
        }
        Text(
            "Comece por um perfil",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Black,
            fontSize = 20.sp,
        )
        Text(
            "Abra alguém em Perto e envie a primeira mensagem. A conversa entra aqui imediatamente, sem match ou aceite.",
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Button(
            onClick = onExplore,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("explore-from-empty-conversations"),
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("Descobrir pessoas", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ConversationDetailScreen(
    currentUserId: String,
    conversation: Conversation,
    profile: DemoProfile?,
    errorMessage: String?,
    onBack: () -> Unit,
    onSendMessage: (String) -> Boolean,
    onSendReply: (String, String) -> Boolean = { _, _ -> false },
    onToggleReaction: (String) -> Unit = {},
    onSendPhoto: (ByteArray) -> Boolean = { false },
    onRetryMessage: (ChatMessage) -> Unit = {},
    onOpenChatPhoto: (String) -> Unit = {},
    chatPhotoPreviewBytes: ByteArray? = null,
    chatPhotoPreviewLoading: Boolean = false,
    onCloseChatPhoto: () -> Unit = {},
    onBlock: (String) -> Unit,
    onReport: (String, ReportReason, String) -> Unit,
    onReportMessage: (String, ReportReason, String, String) -> Unit = { _, _, _, _ -> },
    onToggleMute: (Boolean) -> Unit = {},
    onToggleArchive: (Boolean) -> Unit = {},
    onDeleteConversation: () -> Unit = {},
    approximateRegion: String? = null,
    onTypingChanged: (Boolean) -> Unit = {},
    receivedPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumShared: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onOpenPrivateAlbum: () -> Unit = {},
    onTogglePrivateAlbumShare: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val otherUserId = conversation.participantIds.firstOrNull { it != currentUserId } ?: return
    val displayName = profile?.name ?: "Conversa"
    var message by rememberSaveable(conversation.id) { mutableStateOf("") }
    var showBlockConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showLocationConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var replyMessage by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    var showPhotoPolicy by rememberSaveable { mutableStateOf(false) }
    var safetyMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var albumMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var mediaMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val albumActionAvailable = receivedPrivateAlbumAvailable || myPrivateAlbumAvailable
    val identityColors = profile?.colors ?: listOf(Pink, SurfaceRaised)
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val jpeg = withContext(Dispatchers.IO) {
                        ProfilePhotoProcessor.prepareJpeg(context.contentResolver, uri)
                    }
                    onSendPhoto(jpeg)
                } catch (_: Exception) {
                    // The owning screen keeps the existing privacy-safe generic error treatment.
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .imePadding()
            .testTag("conversation-detail"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Brush.horizontalGradient(identityColors)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back-conversation")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = profile != null, onClick = onOpenProfile)
                    .padding(vertical = 3.dp)
                    .testTag("open-profile-from-conversation"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConversationAvatar(profile = profile, fallbackName = displayName)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayName,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (profile?.verified == true) {
                            Spacer(Modifier.width(5.dp))
                            Icon(
                                Icons.Outlined.Verified,
                                "18+ verificado",
                                tint = Pink,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    Text(
                        if (conversation.participantTyping) "digitando…" else profile?.distance ?: "Conversa ativa",
                        color = ActiveMint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { albumMenuExpanded = true },
                    enabled = albumActionAvailable,
                    modifier = Modifier.testTag("conversation-album-menu"),
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        "Álbum",
                        tint = if (albumActionAvailable) Pink else TextSecondary.copy(alpha = 0.45f),
                    )
                }
                DropdownMenu(
                    expanded = albumMenuExpanded,
                    onDismissRequest = { albumMenuExpanded = false },
                    containerColor = SurfaceRaised,
                ) {
                    if (receivedPrivateAlbumAvailable) {
                        DropdownMenuItem(
                            text = { Text("Abrir álbum recebido") },
                            leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                            onClick = {
                                albumMenuExpanded = false
                                onOpenPrivateAlbum()
                            },
                            modifier = Modifier.testTag("conversation-open-private-album"),
                        )
                    }
                    if (myPrivateAlbumAvailable) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (myPrivateAlbumShared) {
                                        "Revogar meu álbum"
                                    } else {
                                        "Liberar meu álbum"
                                    },
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                            onClick = {
                                albumMenuExpanded = false
                                onTogglePrivateAlbumShare()
                            },
                            modifier = Modifier.testTag("conversation-toggle-private-album"),
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { safetyMenuExpanded = true },
                    modifier = Modifier.testTag("conversation-safety-menu"),
                ) {
                    Icon(Icons.Outlined.MoreVert, "Segurança e opções", tint = TextSecondary)
                }
                DropdownMenu(
                    expanded = safetyMenuExpanded,
                    onDismissRequest = { safetyMenuExpanded = false },
                    containerColor = SurfaceRaised,
                ) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.muted) "Ativar notificações" else "Silenciar conversa") },
                        leadingIcon = {
                            Icon(
                                if (conversation.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                                null,
                            )
                        },
                        onClick = {
                            safetyMenuExpanded = false
                            onToggleMute(!conversation.muted)
                        },
                        modifier = Modifier.testTag("toggle-conversation-mute"),
                    )
                    DropdownMenuItem(
                        text = { Text(if (conversation.archived) "Desarquivar conversa" else "Arquivar conversa") },
                        leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                        onClick = {
                            safetyMenuExpanded = false
                            onToggleArchive(!conversation.archived)
                        },
                        modifier = Modifier.testTag("toggle-conversation-archive"),
                    )
                    DropdownMenuItem(
                        text = { Text("Excluir conversa") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = {
                            safetyMenuExpanded = false
                            showDeleteConfirmation = true
                        },
                        modifier = Modifier.testTag("delete-conversation"),
                    )
                    DropdownMenuItem(
                        text = { Text("Bloquear perfil") },
                        leadingIcon = { Icon(Icons.Outlined.Block, null) },
                        onClick = {
                            safetyMenuExpanded = false
                            showBlockConfirmation = true
                        },
                        modifier = Modifier.testTag("block-active-conversation"),
                    )
                    DropdownMenuItem(
                        text = { Text("Denunciar conversa") },
                        leadingIcon = { Icon(Icons.Outlined.Flag, null) },
                        onClick = {
                            safetyMenuExpanded = false
                            showReportDialog = true
                        },
                        modifier = Modifier.testTag("report-active-conversation"),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF130D13),
                            Black,
                            Color(0xFF100C11),
                        ),
                    ),
                ),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true,
            ) {
                items(conversation.messages.asReversed(), key = { it.id }) { chatMessage ->
                    MessageBubble(
                        message = chatMessage,
                        isMine = chatMessage.senderId == currentUserId,
                        onRetry = { onRetryMessage(chatMessage) },
                        onOpenPhoto = { onOpenChatPhoto(chatMessage.id) },
                        onReport = { reportMessage = chatMessage },
                        onReply = { replyMessage = chatMessage },
                        onToggleReaction = { onToggleReaction(chatMessage.id) },
                        onOpenAlbum = onOpenPrivateAlbum,
                    )
                }
            }
        }

        errorMessage?.let {
            Text(
                it,
                color = Pink,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
        MaterialSurface(
            color = Surface,
            shadowElevation = 14.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                replyMessage?.let { replied ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(SurfaceRaised).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Respondendo", color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (replied.kind == ChatMessageKind.Photo) "Foto" else replied.body,
                                color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { replyMessage = null }, modifier = Modifier.testTag("cancel-message-reply")) {
                            Text("Cancelar")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                Box {
                    IconButton(
                        onClick = { mediaMenuExpanded = true },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceRaised)
                            .testTag("conversation-media-menu"),
                    ) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "Foto ou álbum", tint = Pink)
                    }
                    DropdownMenu(
                        expanded = mediaMenuExpanded,
                        onDismissRequest = { mediaMenuExpanded = false },
                        containerColor = SurfaceRaised,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Selecionar foto") },
                            leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                            onClick = {
                                mediaMenuExpanded = false
                                showPhotoPolicy = true
                            },
                            modifier = Modifier.testTag("conversation-select-photo"),
                        )
                        if (!approximateRegion.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Compartilhar região aproximada") },
                                leadingIcon = { Icon(Icons.Outlined.LocationOn, null) },
                                onClick = {
                                    mediaMenuExpanded = false
                                    showLocationConfirmation = true
                                },
                                modifier = Modifier.testTag("conversation-share-location"),
                            )
                        }
                        if (myPrivateAlbumAvailable) {
                            DropdownMenuItem(
                                text = { Text(if (myPrivateAlbumShared) "Revogar meu álbum" else "Liberar meu álbum") },
                                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                                onClick = {
                                    mediaMenuExpanded = false
                                    onTogglePrivateAlbumShare()
                                },
                                modifier = Modifier.testTag("conversation-media-toggle-album"),
                            )
                        }
                        if (receivedPrivateAlbumAvailable) {
                            DropdownMenuItem(
                                text = { Text("Abrir álbum recebido") },
                                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                                onClick = {
                                    mediaMenuExpanded = false
                                    onOpenPrivateAlbum()
                                },
                                modifier = Modifier.testTag("conversation-media-open-album"),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = {
                        message = it
                        onTypingChanged(it.isNotBlank())
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag("active-message-input"),
                    placeholder = { Text("Escreva uma mensagem") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(20.dp),
                )
                IconButton(
                    onClick = {
                        val sent = replyMessage?.let { onSendReply(message, it.id) } ?: onSendMessage(message)
                        if (sent) {
                            message = ""
                            replyMessage = null
                        }
                    },
                    enabled = message.isNotBlank(),
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (message.isNotBlank()) Pink else SurfaceRaised)
                        .testTag("send-active-message"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        "Enviar",
                        tint = if (message.isNotBlank()) Black else TextSecondary,
                    )
                }
            }
            }
        }
    }

    if (showBlockConfirmation) {
        BlockConfirmationDialog(
            profileName = displayName,
            onDismiss = { showBlockConfirmation = false },
            onConfirm = {
                showBlockConfirmation = false
                onBlock(otherUserId)
            },
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = Surface,
            title = { Text("Excluir conversa?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Ela sairá apenas da sua lista. A outra pessoa continuará com o histórico e uma nova mensagem fará a conversa reaparecer.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteConversation()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Pink),
                    modifier = Modifier.testTag("confirm-delete-conversation"),
                ) { Text("Excluir", color = Black) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancelar") }
            },
        )
    }
    if (showLocationConfirmation) {
        val regionLabel = approximateRegion.orEmpty().replace('-', ' ').uppercase()
        AlertDialog(
            onDismissRequest = { showLocationConfirmation = false },
            containerColor = Surface,
            title = { Text("Compartilhar região aproximada?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Será enviada apenas a região $regionLabel. Coordenadas e localização em tempo real não serão compartilhadas.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationConfirmation = false
                        onSendMessage("Minha localização aproximada: $regionLabel")
                    },
                    modifier = Modifier.testTag("confirm-share-location"),
                ) { Text("Compartilhar") }
            },
            dismissButton = { TextButton(onClick = { showLocationConfirmation = false }) { Text("Cancelar") } },
        )
    }
    if (chatPhotoPreviewLoading || chatPhotoPreviewBytes != null) {
        AlertDialog(
            onDismissRequest = onCloseChatPhoto,
            containerColor = Black,
            title = { Text("Foto da conversa", fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 560.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SurfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (chatPhotoPreviewBytes != null) {
                        AsyncImage(
                            model = chatPhotoPreviewBytes,
                            contentDescription = "Foto privada da conversa",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("Carregando foto…", color = TextSecondary)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onCloseChatPhoto) { Text("Fechar") } },
        )
    }
    if (showPhotoPolicy) {
        AlertDialog(
            onDismissRequest = { showPhotoPolicy = false },
            containerColor = Surface,
            title = { Text("Enviar uma foto", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A foto fica somente nesta conversa e passa por moderação. Conteúdo sem consentimento, com menores ou abusivo é proibido.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPhotoPolicy = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.testTag("confirm-chat-photo-picker"),
                ) { Text("Escolher foto") }
            },
            dismissButton = { TextButton(onClick = { showPhotoPolicy = false }) { Text("Cancelar") } },
        )
    }
    if (showReportDialog) {
        ReportDialog(
            profileName = displayName,
            onDismiss = { showReportDialog = false },
            onConfirm = { reason, details ->
                showReportDialog = false
                onReport(otherUserId, reason, details)
            },
        )
    }
    reportMessage?.let { selectedMessage ->
        ReportDialog(
            profileName = if (selectedMessage.kind == ChatMessageKind.Photo) "esta foto" else "esta mensagem",
            onDismiss = { reportMessage = null },
            onConfirm = { reason, details ->
                reportMessage = null
                onReportMessage(otherUserId, reason, details, selectedMessage.id)
            },
        )
    }
}

@Composable
internal fun ConversationAvatar(profile: DemoProfile?, fallbackName: String) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    profile?.colors ?: listOf(Color(0xFF4C243A), Color(0xFF9C3E68)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (profile?.avatarUrl != null) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = "Foto pública de ${profile.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                profile?.initials ?: fallbackName.trim().take(2).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isMine: Boolean,
    onRetry: () -> Unit,
    onOpenPhoto: () -> Unit,
    onReport: () -> Unit,
    onReply: () -> Unit,
    onToggleReaction: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .widthIn(max = 520.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isMine) 20.dp else 5.dp,
                        bottomEnd = if (isMine) 5.dp else 20.dp,
                    ),
                )
                .background(if (isMine) Pink else SurfaceRaised)
                .combinedClickable(
                    onClick = {
                        when {
                            message.deliveryStatus == ChatDeliveryStatus.Failed -> onRetry()
                            message.kind == ChatMessageKind.Photo && message.mediaStatus == ChatMediaStatus.Approved -> onOpenPhoto()
                        }
                    },
                    onLongClick = { if (!isMine) onReport() },
                )
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            message.replyPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, null, modifier = Modifier.size(14.dp), tint = if (isMine) Black.copy(.7f) else Pink)
                    Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                        color = if (isMine) Black.copy(.7f) else TextSecondary)
                }
            }
            if (message.albumEvent != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = message.albumEvent == "shared", onClick = onOpenAlbum)
                        .testTag("album-event-${message.id}"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.PhotoLibrary, null, tint = if (isMine) Black else Pink)
                        Text(
                            if (message.albumEvent == "shared") "Álbum privado liberado" else "Acesso ao álbum revogado",
                            color = if (isMine) Black else MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (message.albumEvent == "shared") Text("Toque para abrir", fontSize = 11.sp,
                        color = if (isMine) Black.copy(.7f) else TextSecondary)
                }
            } else if (message.kind == ChatMessageKind.Photo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.testTag("message-${message.id}"),
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, tint = if (isMine) Black else Pink)
                    Column {
                        Text(
                            when (message.mediaStatus) {
                                ChatMediaStatus.Approved -> "Foto"
                                ChatMediaStatus.Adult, ChatMediaStatus.Abusive, ChatMediaStatus.Removed -> "Foto indisponível"
                                else -> "Foto em análise"
                            },
                            color = if (isMine) Black else MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (message.mediaStatus == ChatMediaStatus.Approved) "Toque para abrir" else "A prévia aparece após a moderação",
                            color = if (isMine) Black.copy(alpha = 0.7f) else TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            } else {
                Text(
                    text = message.body,
                    color = if (isMine) Black else MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.testTag("message-${message.id}"),
                )
            }
            if (message.deliveryStatus != ChatDeliveryStatus.Sending && message.deliveryStatus != ChatDeliveryStatus.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReply, modifier = Modifier.testTag("reply-message-${message.id}")) {
                        Icon(Icons.AutoMirrored.Outlined.Reply, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Responder", fontSize = 10.sp)
                    }
                    TextButton(onClick = onToggleReaction, modifier = Modifier.testTag("react-message-${message.id}")) {
                        Icon(
                            if (message.reactedByMe) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            null, modifier = Modifier.size(14.dp),
                        )
                        if (message.reactionCount > 0) {
                            Spacer(Modifier.width(3.dp))
                            Text(message.reactionCount.toString(), fontSize = 10.sp)
                        }
                    }
                }
            }
            if (isMine) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (label, icon) = when (message.deliveryStatus) {
                        ChatDeliveryStatus.Sending -> "Enviando" to Icons.Outlined.HourglassTop
                        ChatDeliveryStatus.Delivered -> "Entregue" to Icons.Outlined.DoneAll
                        ChatDeliveryStatus.Read -> "Lida" to Icons.Outlined.DoneAll
                        ChatDeliveryStatus.Failed -> "Falhou · tentar novamente" to Icons.Outlined.Replay
                        ChatDeliveryStatus.Sent -> "Enviada" to Icons.Outlined.DoneAll
                    }
                    Text(label, color = Black.copy(alpha = 0.68f), fontSize = 9.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(icon, null, tint = Black.copy(alpha = 0.68f), modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
internal fun BlockConfirmationDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Bloquear $profileName?", fontWeight = FontWeight.Bold) },
        text = { Text("Vocês deixam de aparecer um para o outro e novas mensagens são impedidas.", color = TextSecondary) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                modifier = Modifier.testTag("confirm-block"),
            ) { Text("Bloquear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun ReportDialog(
    profileName: String,
    onDismiss: () -> Unit,
    onConfirm: (ReportReason, String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf(ReportReason.Spam) }
    var details by rememberSaveable { mutableStateOf("") }
    val reasons = listOf(
        ReportReason.Spam to "Spam",
        ReportReason.Harassment to "Assédio",
        ReportReason.FakeProfile to "Perfil falso",
        ReportReason.Other to "Outro",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Denunciar $profileName", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Escolha o motivo. A denúncia entra na fila de moderação.", color = TextSecondary, fontSize = 13.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    reasons.forEach { (value, label) ->
                        FilterChip(
                            selected = reason == value,
                            onClick = { reason = value },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report-details"),
                    label = { Text("Detalhes (opcional)") },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason, details) },
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                modifier = Modifier.testTag("confirm-report"),
            ) { Text("Enviar denúncia") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
