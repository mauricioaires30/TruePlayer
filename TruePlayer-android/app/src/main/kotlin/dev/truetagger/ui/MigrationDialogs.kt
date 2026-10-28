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
import dev.truetagger.android.FolderOrganizer
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
    TruePlayerTheme {
        when {
            accessNeeded -> AlertDialog(
                onDismissRequest = onAccessLater,
                title = { Text("Libere a pasta de novo") },
                text = {
                    Text(
                        "Escolha a mesma pasta de antes e toque em \"Usar esta pasta\".",
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
                        Text("Não feche o app.", style = MaterialTheme.typography.bodySmall)
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
                            "${plural(preview.songs, "música", "músicas")} e ${plural(preview.playlists, "playlist", "playlists")} fora de songs e playlists. Mover para lá?",
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Os arquivos só mudam de lugar, nada é apagado. O que não der para mover fica onde está.",
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
                        Text("Movidas: ${plural(report.songsMoved, "música", "músicas")} e ${plural(report.playlistsMoved, "playlist", "playlists")}. Nada foi apagado.")
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

/** Lista o que a limpeza vai mover para `_lixeira`; o usuário desmarca o que quiser manter. */
@Composable
fun FolderCleanupDialog(
    scan: FolderOrganizer.Scan,
    onConfirm: (List<FolderOrganizer.Candidate>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val checked = androidx.compose.runtime.remember(scan) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply { scan.candidates.forEach { put(it.name, true) } }
    }
    TruePlayerTheme {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Limpar a pasta") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Nada aqui é música nem playlist. Vai tudo para a _lixeira, sem apagar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    scan.candidates.forEach { item ->
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked[item.name] == true,
                                onCheckedChange = { checked[item.name] = it },
                            )
                            Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                val size = android.text.format.Formatter.formatShortFileSize(context, item.sizeBytes)
                                Text(
                                    if (item.isDir) "${plural(item.files, "arquivo", "arquivos")}, $size" else size,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                item.samples.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (scan.foldersWithMusic.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Não mexi em ${scan.foldersWithMusic.joinToString(", ")}: tem música dentro. " +
                                "Use \"Organizar pasta\" para levar essas músicas para songs.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                val selected = scan.candidates.filter { checked[it.name] == true }
                TextButton(enabled = selected.isNotEmpty(), onClick = { onConfirm(selected) }) {
                    Text("Mover ${selected.size} para a lixeira")
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
        )
    }
}
