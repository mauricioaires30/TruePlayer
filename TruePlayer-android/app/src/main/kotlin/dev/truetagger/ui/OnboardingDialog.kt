package dev.truetagger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.truetagger.model.PermissionItem

private const val STEPS = 4

/**
 * Tutorial de primeira abertura, em passos curtos. Só aparece para quem acabou de instalar o app
 * e ainda não tem pasta (a regra está em `OnboardingRule`). Pode ser pulado a qualquer momento.
 */
@Composable
fun OnboardingDialog(
    folderLabel: String,
    permissions: List<PermissionItem>,
    onChooseFolder: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        // Sem fechar ao tocar fora: o tutorial só sai por "Pular" ou "Concluir".
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Card(
                shape = RoundedCornerShape(UiTokens.cardRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(UiTokens.dialogPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    Text("${step + 1} de $STEPS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                        when (step) {
                            0 -> {
                                Title("Bem-vindo ao TruePlayer")
                                Text("Toca as músicas da sua pasta, edita as tags e baixa por link. Falta pouco para começar.")
                            }
                            1 -> {
                                Title("Escolha a pasta das músicas")
                                Text("É nela que ficam as músicas (songs) e as playlists (playlists). Trocar de celular não perde nada.")
                                Text(
                                    folderLabel.ifBlank { "Nenhuma pasta escolhida" },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ActionButton(
                                    if (folderLabel.isBlank()) "Escolher pasta" else "Trocar pasta",
                                    Icons.Rounded.Folder,
                                    onChooseFolder,
                                    Modifier.fillMaxWidth(),
                                )
                            }
                            2 -> {
                                Title("Permissões")
                                Text("Toque em Conceder nas que faltam.")
                                permissions.forEach { item ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.label, modifier = Modifier.weight(1f))
                                        if (item.granted) {
                                            Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                        } else {
                                            TextButton(onClick = item.onGrant) { Text("Conceder") }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Title("Baixe por link")
                                Text("No YouTube ou no SoundCloud, toque em Compartilhar e escolha o TruePlayer. Playlists também. As tags são ajustadas sozinhas.")
                                Text(
                                    "O app avisa quando sair versão nova. Cada opção tem uma explicação em Configurações.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(UiTokens.space1))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (step < STEPS - 1) {
                            TextButton(onClick = onFinish) { Text("Pular") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (step > 0) {
                            TextButton(onClick = { step -= 1 }) { Text("Voltar") }
                        }
                        Button(
                            onClick = { if (step < STEPS - 1) step += 1 else onFinish() },
                            modifier = Modifier.height(UiTokens.controlHeight),
                            shape = RoundedCornerShape(UiTokens.buttonRadius),
                        ) { Text(if (step < STEPS - 1) "Próximo" else "Concluir") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}
