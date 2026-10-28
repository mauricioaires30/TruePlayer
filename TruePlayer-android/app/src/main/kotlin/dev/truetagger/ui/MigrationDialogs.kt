package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.truetagger.android.FolderMigrator

/** Pergunta, mostra o progresso e o relatório da organização da pasta em songs/ e playlists/. */
@Composable
fun FolderMigrationDialogs(
    preview: FolderMigrator.Preview?,
    progress: Pair<Int, Int>?,
    report: FolderMigrator.Report?,
    message: String?,
    onMessageDismiss: () -> Unit,
    accessNeeded: Boolean,
    onGrantAccess: () -> Unit,
    onAccessLater: () -> Unit,
    onConfirm: () -> Unit,
    onLater: () -> Unit,
    onReportDismiss: () -> Unit,
) {
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        when {
            accessNeeded -> AlertDialog(
                onDismissRequest = onAccessLater,
                title = { Text("Autorize sua pasta de músicas") },
                text = {
                    Text(
                        "O Android não guarda a permissão de acesso à pasta quando o app é restaurado em outro " +
                            "celular. Toque em Autorizar e confirme a mesma pasta de antes (use \"Usar esta pasta\"). " +
                            "Nenhuma música é alterada.",
                    )
                },
                confirmButton = { TextButton(onClick = onGrantAccess) { Text("Autorizar") } },
                dismissButton = { TextButton(onClick = onAccessLater) { Text("Depois") } },
            )

            progress != null -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Organizando a pasta…") },
                text = {
                    Column {
                        Text("Movendo ${progress.first} de ${progress.second}. Não feche o app; a tela fica ligada e nada é apagado.")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress.first / progress.second.coerceAtLeast(1).toFloat() })
                    }
                },
                confirmButton = {},
            )

            preview != null -> AlertDialog(
                onDismissRequest = onLater,
                title = { Text("Organizar sua pasta de músicas") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Criei as pastas songs e playlists. Encontrei ${preview.songs} música(s) e " +
                                "${preview.playlists} playlist(s) fora delas.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Vou apenas mover os arquivos para dentro dessas pastas (sem copiar, apagar ou sobrescrever) " +
                                "e atualizar suas playlists para continuarem apontando para as mesmas músicas. " +
                                "Um registro de tudo fica em .trueplayer. O que não puder ser movido continua onde está.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onConfirm) { Text("Organizar agora") } },
                dismissButton = { TextButton(onClick = onLater) { Text("Depois") } },
            )

            message != null -> AlertDialog(
                onDismissRequest = onMessageDismiss,
                title = { Text("Organizar pasta") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } },
                confirmButton = { TextButton(onClick = onMessageDismiss) { Text("OK") } },
            )

            report != null -> AlertDialog(
                onDismissRequest = onReportDismiss,
                title = { Text(if (report.failed.isEmpty()) "Pasta organizada" else "Organizada, com avisos") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("${report.songsMoved} música(s) e ${report.playlistsMoved} playlist(s) movidas. Nenhum arquivo foi apagado.")
                        if (report.failed.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("${report.failed.size} item(ns) ficaram onde estavam:", style = MaterialTheme.typography.bodySmall)
                            report.failed.take(8).forEach {
                                Text("• ${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (report.failed.size > 8) Text("… e mais ${report.failed.size - 8}.", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Registro: .trueplayer/${report.logName}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = onReportDismiss) { Text("OK") } },
            )
        }
    }
}
