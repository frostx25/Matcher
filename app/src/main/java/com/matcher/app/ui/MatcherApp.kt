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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matcher.app.domain.chat.ChatRequestRepository
import com.matcher.app.domain.chat.ChatRequestResult
import com.matcher.app.domain.chat.InMemoryChatRequestRepository
import com.matcher.app.domain.profile.LocalProfileStore

internal val Black = Color(0xFF0B0B0F)
internal val Surface = Color(0xFF17131A)
internal val Pink = Color(0xFFFF2D7A)
internal val TextSecondary = Color(0xFFB9AEB5)
private const val DemoUserId = "user-free"

private data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val distance: String,
    val intent: String,
    val initials: String,
    val colors: List<Color>,
    val verified: Boolean = false,
)

private val demoProfiles = listOf(
    Profile("maya", "Maya", 28, "perto de você", "Conhecer pessoas", "MA", listOf(Color(0xFF6A163A), Color(0xFFEA5A8C)), true),
    Profile("leo", "Leo", 31, "na região", "Conversa leve", "LE", listOf(Color(0xFF251A59), Color(0xFF7367D9))),
    Profile("noah", "Noah", 26, "na região", "Namoro", "NO", listOf(Color(0xFF164E4A), Color(0xFF55C4B6)), true),
    Profile("bia", "Bia", 30, "mais distante", "Amizade", "BI", listOf(Color(0xFF573519), Color(0xFFD9904D))),
    Profile("alex", "Alex", 29, "perto de você", "Conhecer pessoas", "AL", listOf(Color(0xFF3C245A), Color(0xFFB56BE0))),
    Profile("sam", "Sam", 34, "na região", "Conversa leve", "SA", listOf(Color(0xFF173D63), Color(0xFF4EA6DB))),
)

@Composable
fun MatcherApp(chatRequestRepository: ChatRequestRepository? = null) {
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

    val repository = chatRequestRepository ?: remember { InMemoryChatRequestRepository(initialQuota = 5) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var remainingChats by rememberSaveable { mutableIntStateOf(repository.remainingQuota) }
    var selectedProfile by remember { mutableStateOf<Profile?>(null) }
    var chatError by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Black,
        bottomBar = {
            BottomNavigationBar(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        },
    ) { padding ->
        when (selectedTab) {
            0 -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("discovery-grid"),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Header(remainingChats = remainingChats)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Filters()
                }
                items(demoProfiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onOpenChat = {
                            selectedProfile = profile
                            chatError = null
                        },
                    )
                }
            }

            1 -> ConversationsScreen(modifier = Modifier.padding(padding))
            else -> ProfileScreen(profile = savedProfile, modifier = Modifier.padding(padding))
        }
    }

    selectedProfile?.let { profile ->
        ChatRequestDialog(
            profile = profile,
            remainingChats = remainingChats,
            errorMessage = chatError,
            onDismiss = { selectedProfile = null },
            onSend = { message ->
                when (val result = repository.createChatRequest(DemoUserId, profile.id, message)) {
                    is ChatRequestResult.Created -> {
                        remainingChats = result.remainingQuota
                        chatError = null
                        selectedProfile = null
                    }

                    is ChatRequestResult.AlreadyExists -> {
                        remainingChats = result.remainingQuota
                        chatError = "Você já iniciou uma conversa com este perfil."
                    }

                    is ChatRequestResult.QuotaExhausted -> {
                        remainingChats = result.remainingQuota
                        chatError = "Seu limite de novas conversas foi atingido. Conversas existentes continuam liberadas."
                    }

                    is ChatRequestResult.InvalidMessage -> {
                        chatError = "Escreva uma mensagem antes de enviar."
                    }
                }
            },
        )
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
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Conexões perto de você",
                color = TextSecondary,
                fontSize = 14.sp,
            )
        }

        QuotaPill(remainingChats = remainingChats)
    }
}

@Composable
private fun QuotaPill(remainingChats: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF291722))
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("chat-quota"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            tint = Pink,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$remainingChats chats",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
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
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
private fun ProfileCard(profile: Profile, onOpenChat: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(profile.colors))
            .clickable(onClick = onOpenChat)
            .testTag("profile-${profile.id}"),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("•••", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Text(
            text = profile.initials,
            modifier = Modifier.align(Alignment.Center),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${profile.name}, ${profile.age}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (profile.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Verified, contentDescription = "Verificado", tint = Pink, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(profile.distance, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1)
            Text(profile.intent, color = Color.White.copy(alpha = 0.88f), fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ChatRequestDialog(
    profile: Profile,
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
        title = { Text("Conversar com ${profile.name}") },
        text = {
            Column {
                Text(
                    text = "Envie uma primeira mensagem. A pessoa poderá aceitar, ignorar, bloquear ou denunciar.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
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
                Text("Você tem $remainingChats novas conversas hoje.", color = TextSecondary, fontSize = 12.sp)
                quotaMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = Pink,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("chat-error"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(message) },
                enabled = canSend,
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
                modifier = Modifier.testTag("send-chat-request"),
            ) {
                Text("Enviar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun BottomNavigationBar(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    BottomAppBar(containerColor = Surface) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Outlined.Search, contentDescription = "Descobrir") },
            label = { Text("Descobrir") },
            modifier = Modifier.testTag("tab-discovery"),
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Conversas") },
            label = { Text("Conversas") },
            modifier = Modifier.testTag("tab-conversations"),
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(Icons.Outlined.Person, contentDescription = "Perfil") },
            label = { Text("Perfil") },
            modifier = Modifier.testTag("tab-profile"),
        )
        IconButton(onClick = {}, modifier = Modifier.padding(end = 8.dp)) {
            Icon(Icons.Outlined.Tune, contentDescription = "Configurações", tint = Pink)
        }
    }
}
