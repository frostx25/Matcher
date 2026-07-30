package com.matcher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matcher.app.domain.profile.AgeGate
import com.matcher.app.domain.profile.LocalProfile
import java.time.Year

@Composable
internal fun OnboardingScreen(onComplete: (LocalProfile) -> Unit) {
    val currentYear = remember { Year.now().value }
    var displayName by rememberSaveable { mutableStateOf("") }
    var birthYearInput by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var intent by rememberSaveable { mutableStateOf("Conhecer pessoas") }
    var ageConfirmed by rememberSaveable { mutableStateOf(false) }
    var termsAccepted by rememberSaveable { mutableStateOf(false) }

    val birthYear = birthYearInput.toIntOrNull()
    val isAdult = birthYear?.let { AgeGate.isAdult(it, currentYear) } == true
    val invalidAdultYear = birthYearInput.length == 4 && !isAdult
    val canContinue = displayName.isNotBlank() && isAdult && ageConfirmed && termsAccepted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("onboarding-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Bem-vindo ao Matcher",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = "Conexões entre adultos, com identidade e privacidade no seu controle.",
            color = TextSecondary,
            fontSize = 15.sp,
        )

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("display-name-input"),
            label = { Text("Nome de exibição") },
            singleLine = true,
        )
        OutlinedTextField(
            value = birthYearInput,
            onValueChange = { value ->
                if (value.length <= 4 && value.all(Char::isDigit)) {
                    birthYearInput = value
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("birth-year-input"),
            label = { Text("Ano de nascimento") },
            placeholder = { Text("Ex.: 1995") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        if (invalidAdultYear) {
            Text(
                text = "O Matcher é exclusivo para pessoas com 18 anos ou mais.",
                color = Pink,
                fontSize = 13.sp,
                modifier = Modifier.testTag("age-error"),
            )
        }
        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bio-input"),
            label = { Text("Bio (opcional)") },
            minLines = 3,
        )
        OutlinedTextField(
            value = intent,
            onValueChange = { intent = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("intent-input"),
            label = { Text("O que você busca?") },
            singleLine = true,
        )

        ConsentRow(
            checked = ageConfirmed,
            onCheckedChange = { ageConfirmed = it },
            text = "Confirmo que tenho 18 anos ou mais.",
            tag = "adult-confirmation",
        )
        ConsentRow(
            checked = termsAccepted,
            onCheckedChange = { termsAccepted = it },
            text = "Aceito os Termos de Uso e a Política de Privacidade.",
            tag = "terms-confirmation",
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onComplete(
                    LocalProfile(
                        displayName = displayName.trim(),
                        birthYear = birthYear ?: currentYear,
                        bio = bio.trim(),
                        intent = intent.trim().ifBlank { "Conhecer pessoas" },
                    ),
                )
            },
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("continue-onboarding"),
            colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Black),
        ) {
            Text("Continuar")
        }
        Text(
            text = "Você poderá ajustar seu perfil depois. A localização exata não será exibida.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = text, color = TextSecondary, fontSize = 13.sp)
    }
}
