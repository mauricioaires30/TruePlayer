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
import dev.truetagger.android.t
import dev.truetagger.android.R

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
    TruePlayerTheme {
        // Sem fechar ao tocar fora: o tutorial só sai por "Pular" ou "Concluir".
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Card(
                shape = RoundedCornerShape(UiTokens.cardRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(UiTokens.dialogPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    Text(t(R.string.onb_de, step + 1, STEPS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                        when (step) {
                            0 -> {
                                Title(t(R.string.onb_bem_vindo_ao_trueplayer))
                                Text(t(R.string.onb_toca_as_musicas_da_sua))
                            }
                            1 -> {
                                Title(t(R.string.onb_escolha_a_pasta_das_musicas))
                                Text(t(R.string.onb_e_nela_que_ficam_as))
                                Text(
                                    folderLabel.ifBlank { t(R.string.onb_nenhuma_pasta_escolhida) },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ActionButton(
                                    if (folderLabel.isBlank()) t(R.string.mig_escolher_pasta) else t(R.string.onb_trocar_pasta),
                                    Icons.Rounded.Folder,
                                    onChooseFolder,
                                    Modifier.fillMaxWidth(),
                                )
                            }
                            2 -> {
                                Title(t(R.string.permissoes))
                                Text(t(R.string.onb_toque_em_conceder_nas_que))
                                permissions.forEach { item ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.label, modifier = Modifier.weight(1f))
                                        if (item.granted) {
                                            Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                        } else {
                                            TextButton(onClick = item.onGrant) { Text(t(R.string.conceder)) }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Title(t(R.string.onb_baixe_por_link))
                                Text(t(R.string.onb_no_youtube_ou_no_soundcloud))
                                Text(
                                    t(R.string.onb_o_app_avisa_quando_sair),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(UiTokens.space1))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (step < STEPS - 1) {
                            TextButton(onClick = onFinish) { Text(t(R.string.onb_pular)) }
                        }
                        Spacer(Modifier.weight(1f))
                        if (step > 0) {
                            TextButton(onClick = { step -= 1 }) { Text(t(R.string.voltar)) }
                        }
                        Button(
                            onClick = { if (step < STEPS - 1) step += 1 else onFinish() },
                            modifier = Modifier.height(UiTokens.controlHeight),
                            shape = RoundedCornerShape(UiTokens.buttonRadius),
                        ) { Text(if (step < STEPS - 1) t(R.string.onb_proximo) else t(R.string.onb_concluir)) }
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
