package shop.vibeali.app.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shop.vibeali.app.domain.profile.LocalProfile

@Composable
internal fun ProfileScreen(profile: LocalProfile, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Black)
            .padding(20.dp)
            .testTag("profile-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Seu perfil",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "O que as pessoas veem antes de conversar com você.",
            color = TextSecondary,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${profile.displayName}, ${profile.age}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(profile.intent, color = Pink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = profile.bio.ifBlank { "Adicione uma bio para contar mais sobre você." },
                color = TextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ColorForPrivacy)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Privacidade ativa", color = SoftPink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Sua localização exata não aparece no perfil.", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

private val ColorForPrivacy = androidx.compose.ui.graphics.Color(0xFF201820)
