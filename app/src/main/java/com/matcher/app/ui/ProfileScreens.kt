package com.matcher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matcher.app.domain.profile.LocalProfile

@Composable
internal fun ConversationsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("conversations-screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Conversas",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = "Suas solicitações e conversas ativas aparecerão aqui.",
            color = TextSecondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Nenhuma conversa ativa ainda",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(18.dp))
                .padding(18.dp),
        )
    }
}

@Composable
internal fun ProfileScreen(profile: LocalProfile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
            .testTag("profile-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Seu perfil",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(22.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${profile.displayName}, ${profile.age}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = profile.intent, color = Pink, fontSize = 14.sp)
            Text(
                text = profile.bio.ifBlank { "Adicione uma bio para contar mais sobre você." },
                color = TextSecondary,
                fontSize = 15.sp,
            )
        }
        Text(
            text = "Sua localização exata não aparece no perfil.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}
