package com.matcher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class SubscriptionPlanUi(
    val name: String,
    val price: String,
    val summary: String,
    val features: List<String>,
    val featured: Boolean = false,
)

internal val subscriptionPlans = listOf(
    SubscriptionPlanUi("Free", "R$ 0", "O essencial para entrar na vibe", listOf("5 novas conversas por dia", "20 favoritos", "1 álbum privado com 10 fotos")),
    SubscriptionPlanUi("Extra", "R$ 14,90/mês", "Mais espaço para descobrir", listOf("20 novas conversas por dia", "Filtros avançados", "200 favoritos e 50 vistos recentemente", "Ocultar atividade recente")),
    SubscriptionPlanUi("Pro", "R$ 29,90/mês", "Privacidade e alcance", listOf("50 novas conversas por dia", "Favoritos ilimitados", "Modo incógnito", "Ver quem favoritou você", "1 destaque por semana"), featured = true),
    SubscriptionPlanUi("Ilimitado", "R$ 49,90/mês", "A experiência completa", listOf("Conversas sem limite comercial", "Tudo do Pro", "Até 3 álbuns e 30 fotos", "Histórico de visitas por 30 dias", "1 destaque por dia", "Suporte prioritário")),
)

@Composable
internal fun SubscriptionPlansScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true,
) {
    Column(
        modifier.fillMaxSize().background(Color(0xFF0B0B0F)).verticalScroll(rememberScrollState()).padding(20.dp).testTag("subscription-plans-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("plans-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar")
                }
            }
            Column {
                Text("Escolha sua vibe", fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("Planos claros, sem tirar sua segurança", color = Color(0xFFB9AEB5))
            }
        }
        Surface(color = Color(0xFF251E27), shape = RoundedCornerShape(18.dp)) {
            Text("Assinaturas em breve. Os valores são referências; nenhuma cobrança será feita agora.", Modifier.padding(16.dp), color = Color(0xFFFF78A8))
        }
        subscriptionPlans.forEach { plan -> PlanCard(plan) }
        Text(
            "Bloquear, denunciar, excluir a conta, responder conversas e controlar seu álbum continuam disponíveis no Free. Limites de segurança valem em todos os planos.",
            color = Color(0xFFB9AEB5), fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PlanCard(plan: SubscriptionPlanUi) {
    val outline = if (plan.featured) Color(0xFFFF2D7A) else Color(0xFF3B303D)
    Column(
        Modifier.fillMaxWidth().border(if (plan.featured) 2.dp else 1.dp, outline, RoundedCornerShape(24.dp)).background(Color(0xFF17131A), RoundedCornerShape(24.dp)).padding(18.dp).testTag("plan-${plan.name.lowercase() }"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (plan.featured) Text("RECOMENDADO", color = Color(0xFFFF78A8), fontSize = 11.sp, fontWeight = FontWeight.Black)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(plan.name, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text(plan.price, color = Color(0xFFFF78A8), fontWeight = FontWeight.Bold)
        }
        Text(plan.summary, color = Color(0xFFB9AEB5))
        plan.features.forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFFFF2D7A))
                Text(it)
            }
        }
        Button(
            onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF302832), disabledContentColor = Color(0xFFB9AEB5)),
        ) { Text(if (plan.name == "Free") "Seu plano inicial" else "Em breve") }
    }
}
