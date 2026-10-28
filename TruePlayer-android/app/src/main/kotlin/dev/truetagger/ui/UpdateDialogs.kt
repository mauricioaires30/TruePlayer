package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.truetagger.android.UpdateUi
import dev.truetagger.data.ReleaseInfo

/** Avisos de atualização do app e "o que mudou" depois de atualizar. */
@Composable
fun UpdateDialogs(
    state: UpdateUi,
    whatsNew: Pair<String, String>?,
    onUpdate: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit,
    onWhatsNewDismiss: () -> Unit,
) {
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        when (state) {
            UpdateUi.Idle -> Unit

            UpdateUi.Checking -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Procurando atualização") },
                text = { LinearProgressIndicator(Modifier.padding(top = 4.dp)) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
            )

            is UpdateUi.UpToDate -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Tudo em dia") },
                text = { Text("Você já está na versão mais recente (${state.version}).") },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )

            is UpdateUi.Available -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Nova versão: ${state.release.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("O que mudou", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(state.release.notes.ifBlank { "Sem notas para esta versão." }, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "O download tem ${formatSize(state.release.apkSizeBytes)}. Suas músicas, playlists e estatísticas não são alteradas.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { onUpdate(state.release) }) { Text("Atualizar") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Depois") } },
            )

            is UpdateUi.Downloading -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Baixando a atualização") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.weight(1f))
                            Text("${state.percent}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Quando terminar, o Android vai pedir para confirmar a instalação.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {},
            )

            is UpdateUi.NeedsInstallPermission -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Libere a instalação") },
                text = {
                    Text(
                        "Para atualizar, o Android precisa que você permita o TruePlayer instalar apps. " +
                            "Toque em Abrir ajustes, ative a opção e volte aqui: a atualização continua sozinha.",
                    )
                },
                confirmButton = { TextButton(onClick = { onUpdate(state.release) }) { Text("Abrir ajustes") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Agora não") } },
            )

            is UpdateUi.Failed -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Atualização") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            )
        }

        if (whatsNew != null && state == UpdateUi.Idle) {
            AlertDialog(
                onDismissRequest = onWhatsNewDismiss,
                title = { Text("Novidades da ${whatsNew.first}") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(whatsNew.second, style = MaterialTheme.typography.bodySmall) } },
                confirmButton = { TextButton(onClick = onWhatsNewDismiss) { Text("OK") } },
            )
        }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes <= 0L) "alguns MB" else "%.0f MB".format(bytes / 1_048_576.0)
