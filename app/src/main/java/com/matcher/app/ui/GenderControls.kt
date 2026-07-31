package com.matcher.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class GenderChoice(
    val id: String,
    val label: String,
)

internal val IdentityGenderChoices = listOf(
    GenderChoice("woman", "Mulher"),
    GenderChoice("man", "Homem"),
    GenderChoice("trans_woman", "Mulher trans"),
    GenderChoice("trans_man", "Homem trans"),
    GenderChoice("non_binary", "Não binárie"),
    GenderChoice("genderqueer", "Gênero queer"),
    GenderChoice("self_described", "Outra identidade"),
    GenderChoice("prefer_not_to_say", "Prefiro não informar"),
)

internal val LookingForGenderChoices = listOf(
    GenderChoice("everyone", "Todas as pessoas"),
) + IdentityGenderChoices.filterNot { it.id == "prefer_not_to_say" }

internal fun toggleGenderChoice(
    current: Set<String>,
    choice: String,
    exclusiveChoice: String,
): Set<String> {
    if (choice == exclusiveChoice) {
        return if (choice in current && current.size == 1) emptySet() else setOf(choice)
    }
    return current
        .minus(exclusiveChoice)
        .let { selected ->
            if (choice in selected) selected - choice else selected + choice
        }
}

internal fun genderLabels(ids: Collection<String>, hidden: Boolean = false): String {
    if (hidden) return "Não exibida"
    val labels = IdentityGenderChoices.associate { it.id to it.label }
    return ids.mapNotNull(labels::get).joinToString().ifBlank { "Não informada" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenderIdentitySelector(
    selected: Set<String>,
    selfDescription: String,
    visible: Boolean,
    enabled: Boolean,
    onSelectedChange: (Set<String>) -> Unit,
    onSelfDescriptionChange: (String) -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Como você se identifica?", color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
        Text(
            "Você pode escolher mais de uma opção. Isso é separado de quem deseja encontrar.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IdentityGenderChoices.forEach { choice ->
                FilterChip(
                    selected = choice.id in selected,
                    onClick = {
                        onSelectedChange(
                            toggleGenderChoice(
                                current = selected,
                                choice = choice.id,
                                exclusiveChoice = "prefer_not_to_say",
                            ),
                        )
                    },
                    enabled = enabled,
                    label = { Text(choice.label) },
                    modifier = Modifier.testTag("identity-${choice.id}"),
                )
            }
        }
        if ("self_described" in selected) {
            OutlinedTextField(
                value = selfDescription,
                onValueChange = { onSelfDescriptionChange(it.take(60)) },
                modifier = Modifier.fillMaxWidth().testTag("gender-self-description"),
                label = { Text("Como prefere descrever") },
                singleLine = true,
                enabled = enabled,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && "prefer_not_to_say" !in selected) {
                    onVisibleChange(!visible)
                }
                .testTag("gender-visible"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = visible && "prefer_not_to_say" !in selected,
                onCheckedChange = onVisibleChange,
                enabled = enabled && "prefer_not_to_say" !in selected,
            )
            Text("Mostrar minha identidade no perfil", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LookingForGenderSelector(
    selected: Set<String>,
    enabled: Boolean,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Quem você quer encontrar?",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
        Text(
            "Essa preferência é privada e pode ter várias opções.",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LookingForGenderChoices.forEach { choice ->
                FilterChip(
                    selected = choice.id in selected,
                    onClick = {
                        onSelectedChange(
                            toggleGenderChoice(
                                current = selected,
                                choice = choice.id,
                                exclusiveChoice = "everyone",
                            ),
                        )
                    },
                    enabled = enabled,
                    label = { Text(choice.label) },
                    modifier = Modifier.testTag("looking-for-${choice.id}"),
                )
            }
        }
    }
}
