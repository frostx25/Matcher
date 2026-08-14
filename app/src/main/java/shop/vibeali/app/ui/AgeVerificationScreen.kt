package shop.vibeali.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shop.vibeali.app.data.remote.AgeVerificationStatus
import shop.vibeali.app.data.remote.ActiveSanction

@Composable
internal fun AgeVerificationScreen(
    status: AgeVerificationStatus,
    consentGranted: Boolean,
    loading: Boolean,
    errorMessage: String?,
    onConsentChanged: (Boolean) -> Unit,
    onStartVerification: () -> Unit,
    onRefreshStatus: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = !loading, onBack = onBack)
    val uriHandler = LocalUriHandler.current
    val canStart = status == AgeVerificationStatus.NotStarted ||
        status == AgeVerificationStatus.Pending ||
        status == AgeVerificationStatus.Failed
    val actionLabel = when (status) {
        AgeVerificationStatus.Pending -> "Continuar verificação"
        AgeVerificationStatus.Failed -> "Tentar novamente"
        else -> "Verificar maioridade"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 36.dp)
            .testTag("age-verification-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Verified,
            contentDescription = null,
            tint = Pink,
        )
        Text(
            text = "Verifique seu perfil",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "Você já pode usar o VibeAli como não verificado. Esta etapa opcional concede o selo 18+ verificado.",
            color = TextSecondary,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )

        VerificationStep(
            icon = { Icon(Icons.Outlined.Badge, null, tint = Pink) },
            title = "1. Documento com foto",
            description = "A Didit valida o documento e a data de nascimento para confirmar que você tem 18+.",
        )
        VerificationStep(
            icon = { Icon(Icons.Outlined.CameraAlt, null, tint = Pink) },
            title = "2. Selfie com prova de vida",
            description = "A selfie ao vivo passa por prova de vida passiva e é comparada à foto do documento.",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = verificationStatusTitle(status),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("age-verification-status"),
            )
            Text(
                text = verificationStatusDescription(status),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }

        if (loading) {
            CircularProgressIndicator(
                color = Pink,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("age-verification-loading"),
            )
        }

        errorMessage?.let {
            Text(
                text = it,
                color = Pink,
                fontSize = 13.sp,
                modifier = Modifier.testTag("age-verification-error"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = consentGranted,
                    onCheckedChange = onConsentChanged,
                    enabled = !loading,
                    modifier = Modifier.testTag("didit-processing-consent"),
                )
                Text(
                    text = "Autorizo a Didit a processar meu documento, minha selfie e dados biométricos para verificar minha identidade e maioridade.",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            TextButton(
                onClick = { runCatching { uriHandler.openUri(DIDIT_VERIFICATION_PRIVACY_NOTICE_URL) } },
                enabled = !loading,
                modifier = Modifier.testTag("didit-privacy-notice-link"),
            ) {
                Text("Aviso de privacidade da verificação")
            }
            TextButton(
                onClick = { runCatching { uriHandler.openUri(DIDIT_IDENTITY_VERIFICATION_TERMS_URL) } },
                enabled = !loading,
                modifier = Modifier.testTag("didit-identity-terms-link"),
            ) {
                Text("Termos da verificação de identidade")
            }
        }

        Button(
            onClick = onStartVerification,
            enabled = canStart && consentGranted && !loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("start-age-verification"),
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(actionLabel, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onRefreshStatus,
            enabled = !loading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("refresh-age-verification"),
        ) {
            Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text("Atualizar status")
        }
        Text(
            text = "A Didit processa o documento e a selfie para realizar a verificação. O VibeAli recebe apenas o resultado necessário e não armazena essas imagens.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        TextButton(
            onClick = onBack,
            enabled = !loading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Voltar ao perfil")
        }
    }
}

internal const val DIDIT_VERIFICATION_PRIVACY_NOTICE_URL =
    "https://didit.me/terms/verification-privacy-notice/"
internal const val DIDIT_IDENTITY_VERIFICATION_TERMS_URL =
    "https://didit.me/terms/identity-verification/"

@Composable
private fun VerificationStep(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceRaised)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Text(description, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun RemoteAccessUnavailableScreen(
    message: String?,
    loading: Boolean,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    activeSanction: ActiveSanction? = null,
    onSubmitAppeal: (String) -> Unit = {},
) {
    var appealStatement by remember(activeSanction?.sanctionId) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(24.dp)
            .testTag("remote-access-unavailable"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Acesso indisponível",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
        if (activeSanction != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (activeSanction.sanctionKind == "ban") "Conta banida" else "Conta suspensa",
                color = Pink,
                fontWeight = FontWeight.Bold,
            )
            if (activeSanction.appealState == null) {
                OutlinedTextField(
                    value = appealStatement,
                    onValueChange = { appealStatement = it.take(2000) },
                    label = { Text("Explique por que deseja uma revisão") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("moderation-appeal-input"),
                )
                OutlinedButton(
                    onClick = { onSubmitAppeal(appealStatement) },
                    enabled = !loading && appealStatement.trim().length >= 20,
                    modifier = Modifier.fillMaxWidth().testTag("submit-moderation-appeal"),
                ) { Text("Enviar recurso") }
            } else {
                Text("Recurso: ${activeSanction.appealState.replace('_', ' ')}", color = TextSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = message ?: "Não foi possível confirmar o estado da sua conta.",
            color = TextSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
        ) {
            Text("Tentar novamente")
        }
        TextButton(onClick = onSignOut, enabled = !loading) { Text("Sair") }
    }
}

@Composable
internal fun RemoteConfigurationErrorScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(24.dp)
            .testTag("remote-configuration-error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "VibeAli indisponível",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "A configuração segura do backend não foi encontrada.",
            color = TextSecondary,
        )
    }
}

private fun verificationStatusTitle(status: AgeVerificationStatus): String = when (status) {
    AgeVerificationStatus.NotStarted -> "Perfil não verificado"
    AgeVerificationStatus.Pending -> "Verificação em andamento"
    AgeVerificationStatus.Failed -> "Não foi possível confirmar"
    AgeVerificationStatus.ManualReview -> "Verificação em análise"
    AgeVerificationStatus.Verified -> "Maioridade verificada"
    AgeVerificationStatus.Unknown -> "Status indisponível"
}

private fun verificationStatusDescription(status: AgeVerificationStatus): String = when (status) {
    AgeVerificationStatus.NotStarted -> "Você pode iniciar a verificação quando quiser."
    AgeVerificationStatus.Pending -> "Conclua a sessão aberta ou atualize o status depois de retornar ao app."
    AgeVerificationStatus.Failed -> "Você pode iniciar uma nova tentativa de verificação."
    AgeVerificationStatus.ManualReview -> "Aguarde a análise. Sua conta continua ativa normalmente."
    AgeVerificationStatus.Verified -> "O backend confirmou o selo 18+ verificado."
    AgeVerificationStatus.Unknown -> "Atualize o status ou tente novamente mais tarde."
}
