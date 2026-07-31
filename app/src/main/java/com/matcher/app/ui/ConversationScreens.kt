package com.matcher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    Text("Conversas", color = MaterialTheme.colorScheme.onBackground, fontSize = 29.sp, fontWeight = FontWeight.Black)
                    Text("Contato direto com bloqueio sempre disponível.", color = TextSecondary, fontSize = 13.sp)
                }
                QuotaPill(remainingChats)
            }
        }

        if (conversations.isNotEmpty()) {
            item { SectionTitle("Ativas", "sem aceite prévio", ActiveMint) }
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
                EmptyConversationCard()
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(detail.uppercase(), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
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
    StatusRailCard(
        accent = ActiveMint,
        modifier = Modifier
            .clickable(onClick = onOpen)
            .testTag("active-${profile.id}"),
    ) {
        ProfileLine(profile = profile, status = "CONVERSA ATIVA", statusColor = ActiveMint)
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                lastMessage == null -> "Conversa iniciada"
                lastMessage.senderId == currentUserId -> "Você: ${lastMessage.body}"
                else -> lastMessage.body
            },
            color = TextSecondary,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusRailCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(22.dp))
            .background(Surface),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ProfileLine(profile: DemoProfile, status: String, statusColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(profile.colors.first()),
            contentAlignment = Alignment.Center,
        ) {
            Text(profile.initials, color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (profile.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Verified, "Verificado", tint = Pink, modifier = Modifier.size(15.dp))
                }
            }
            Text(profile.distance, color = TextSecondary, fontSize = 11.sp)
        }
        Text(status, color = statusColor, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyConversationCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Tudo tranquilo por aqui", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("A primeira mensagem já abre uma conversa. Bloqueio e denúncia ficam disponíveis dentro do chat.", color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
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
) {
    val otherUserId = conversation.participantIds.firstOrNull { it != currentUserId } ?: return
    val displayName = profile?.name ?: "Conversa"
    var message by rememberSaveable(conversation.id) { mutableStateOf("") }
    var showBlockConfirmation by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .statusBarsPadding()
            .imePadding()
            .testTag("conversation-detail"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back-conversation")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Voltar",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Conversa ativa", color = ActiveMint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            IconButton(onClick = { showBlockConfirmation = true }, modifier = Modifier.testTag("block-active-conversation")) {
                Icon(Icons.Outlined.Block, "Bloquear", tint = TextSecondary)
            }
            IconButton(onClick = { showReportDialog = true }, modifier = Modifier.testTag("report-active-conversation")) {
                Icon(Icons.Outlined.Flag, "Denunciar", tint = TextSecondary)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            reverseLayout = true,
        ) {
            items(conversation.messages.asReversed(), key = { it.id }) { chatMessage ->
                MessageBubble(message = chatMessage, isMine = chatMessage.senderId == currentUserId)
            }
        }

        errorMessage?.let {
            Text(it, color = Pink, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                shape = RoundedCornerShape(18.dp),
            )
            IconButton(
                onClick = {
                    if (onSendMessage(message)) message = ""
                },
                enabled = message.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (message.isNotBlank()) Pink else SurfaceRaised)
                    .testTag("send-active-message"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Enviar", tint = if (message.isNotBlank()) Black else TextSecondary)
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
private fun MessageBubble(message: ChatMessage, isMine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 5.dp,
                        bottomEnd = if (isMine) 5.dp else 18.dp,
                    ),
                )
                .background(if (isMine) Pink else SurfaceRaised)
                .padding(horizontal = 14.dp, vertical = 11.dp),
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
