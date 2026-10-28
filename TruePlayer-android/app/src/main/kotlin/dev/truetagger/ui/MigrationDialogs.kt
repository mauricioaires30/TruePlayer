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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.truetagger.android.FolderMigrator
import dev.truetagger.data.PlannedMove

/** Avisos da organização da pasta (songs/ e playlists/) e do acesso à pasta. */
@Composable
fun FolderMigrationDialogs(
    preview: FolderMigrator.Preview?,
    progress: FolderMigrator.Progress?,
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
                title = { Text("Libere a pasta de novo") },
                text = {
                    Text(
                        "Ao trocar de celular o Android pede a permissão da pasta outra vez. " +
                            "Toque em Escolher pasta e confirme a mesma de antes, em \"Usar esta pasta\". " +
                            "Suas músicas não são mexidas.",
                    )
                },
                confirmButton = { TextButton(onClick = onGrantAccess) { Text("Escolher pasta") } },
                dismissButton = { TextButton(onClick = onAccessLater) { Text("Agora não") } },
            )

            progress != null -> AlertDialog(
                onDismissRequest = {},
                title = { Text("Organizando a pasta") },
                text = {
                    Column {
                        val isSong = progress.kind == PlannedMove.Kind.SONG
                        if (progress.name.isBlank()) {
                            Text("Preparando…")
                        } else {
                            Text(if (isSong) "Movendo música" else "Movendo playlist", style = MaterialTheme.typography.bodySmall)
                            Text(progress.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${progress.index} de ${progress.total} " + if (isSong) "músicas" else "playlists",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        val fraction = (progress.overallDone / progress.overallTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
                            Text(
                                "${(fraction * 100).toInt()}%",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Não feche o app, a tela fica ligada enquanto isso.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {},
            )

            preview != null -> AlertDialog(
                onDismissRequest = onLater,
                title = { Text("Organizar a pasta?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Achei ${preview.songs} música(s) e ${preview.playlists} playlist(s) fora das pastas " +
                                "songs e playlists. Posso mover tudo pra dentro delas.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Os arquivos só mudam de lugar: nada é copiado, apagado ou sobrescrito, e suas " +
                                "playlists continuam com as mesmas músicas. O que não der pra mover fica onde está. " +
                                "Tudo que for feito fica registrado em .trueplayer.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onConfirm) { Text("Organizar") } },
                dismissButton = { TextButton(onClick = onLater) { Text("Agora não") } },
            )

            message != null -> AlertDialog(
                onDismissRequest = onMessageDismiss,
                title = { Text("Organizar pasta") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } },
                confirmButton = { TextButton(onClick = onMessageDismiss) { Text("OK") } },
            )

            report != null -> AlertDialog(
                onDismissRequest = onReportDismiss,
                title = { Text(if (report.failed.isEmpty()) "Pasta organizada" else "Organizei, mas teve problema") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("${report.songsMoved} música(s) e ${report.playlistsMoved} playlist(s) movidas. Nada foi apagado.")
                        if (report.failed.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Estes ficaram onde estavam (${report.failed.size}):", style = MaterialTheme.typography.bodySmall)
                            report.failed.take(8).forEach {
                                Text("• ${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (report.failed.size > 8) Text("e mais ${report.failed.size - 8}.", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Registro salvo em .trueplayer/${report.logName}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = onReportDismiss) { Text("OK") } },
            )
        }
    }
}
