package com.matcher.app.ui

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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.matcher.app.domain.chat.ChatMessage
import com.matcher.app.domain.chat.Conversation
import com.matcher.app.domain.chat.ReportReason

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

        if (conversations.isNotEmpty()) {
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
            items(conversations, key = { it.id }) { conversation ->
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

        if (conversations.isEmpty()) {
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
        Icon(Icons.Outlined.ChevronRight, "Abrir conversa", tint = TextSecondary)
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
    onBlock: (String) -> Unit,
    onReport: (String, ReportReason, String) -> Unit,
    receivedPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumAvailable: Boolean = false,
    myPrivateAlbumShared: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onOpenPrivateAlbum: () -> Unit = {},
    onTogglePrivateAlbumShare: () -> Unit = {},
) {
    val otherUserId = conversation.participantIds.firstOrNull { it != currentUserId } ?: return
    val displayName = profile?.name ?: "Conversa"
    var message by rememberSaveable(conversation.id) { mutableStateOf("") }
    var showBlockConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    var safetyMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var albumMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    val albumActionAvailable = receivedPrivateAlbumAvailable || myPrivateAlbumAvailable
    val identityColors = profile?.colors ?: listOf(Pink, SurfaceRaised)

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
                        profile?.distance ?: "Conversa ativa",
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
                        text = { Text("Bloquear perfil") },
                        leadingIcon = { Icon(Icons.Outlined.Block, null) },
                        onClick = {
                            safetyMenuExpanded = false
                            showBlockConfirmation = true
                        },
                        modifier = Modifier.testTag("block-active-conversation"),
                    )
                    DropdownMenuItem(
                        text = { Text("Denunciar perfil") },
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
                    MessageBubble(message = chatMessage, isMine = chatMessage.senderId == currentUserId)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
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
                        if (onSendMessage(message)) message = ""
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
private fun MessageBubble(message: ChatMessage, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
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
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            Text(
                text = message.body,
                color = if (isMine) Black else MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                modifier = Modifier.testTag("message-${message.id}"),
            )
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
