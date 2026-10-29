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
import dev.truetagger.android.t
import dev.truetagger.android.tp
import dev.truetagger.android.R

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
                title = { Text(t(R.string.mig_libere_a_pasta_de_novo)) },
                text = {
                    Text(
                        t(R.string.mig_escolha_a_mesma_pasta_de),
                    )
                },
                confirmButton = { TextButton(onClick = onGrantAccess) { Text(t(R.string.mig_escolher_pasta)) } },
                dismissButton = { TextButton(onClick = onAccessLater) { Text(t(R.string.mig_agora_nao)) } },
            )

            progress != null -> AlertDialog(
                onDismissRequest = {},
                title = { Text(t(R.string.mig_organizando_a_pasta)) },
                text = {
                    Column {
                        val isSong = progress.kind == PlannedMove.Kind.SONG
                        if (progress.name.isBlank()) {
                            Text(t(R.string.mig_preparando))
                        } else {
                            Text(if (isSong) t(R.string.mig_movendo_musica) else t(R.string.mig_movendo_playlist), style = MaterialTheme.typography.bodySmall)
                            Text(progress.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                t(R.string.mig_de, progress.index, progress.total) + if (isSong) t(R.string.mig_musicas) else "playlists",
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
                        Text(t(R.string.mig_nao_feche_o_app), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {},
            )

            preview != null -> AlertDialog(
                onDismissRequest = onLater,
                title = { Text(t(R.string.mig_organizar_a_pasta)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            t(R.string.mig_e_fora_de_songs_e, tp(R.plurals.n_musicas, preview.songs), tp(R.plurals.n_playlists, preview.playlists)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t(R.string.mig_os_arquivos_so_mudam_de),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = onConfirm) { Text(t(R.string.mig_organizar)) } },
                dismissButton = { TextButton(onClick = onLater) { Text(t(R.string.mig_agora_nao)) } },
            )

            message != null -> AlertDialog(
                onDismissRequest = onMessageDismiss,
                title = { Text(t(R.string.organizar_pasta)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } },
                confirmButton = { TextButton(onClick = onMessageDismiss) { Text("OK") } },
            )

            report != null -> AlertDialog(
                onDismissRequest = onReportDismiss,
                title = { Text(if (report.failed.isEmpty()) t(R.string.mig_pasta_organizada) else t(R.string.mig_organizei_mas_teve_problema)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(t(R.string.mig_movidas_e_nada_foi_apagado, tp(R.plurals.n_musicas, report.songsMoved), tp(R.plurals.n_playlists, report.playlistsMoved)))
                        if (report.failed.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(t(R.string.mig_estes_ficaram_onde_estavam, report.failed.size), style = MaterialTheme.typography.bodySmall)
                            report.failed.take(8).forEach {
                                Text("• ${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (report.failed.size > 8) Text(t(R.string.mig_e_mais, report.failed.size - 8), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t(R.string.mig_registro_salvo_em_trueplayer, report.logName), style = MaterialTheme.typography.bodySmall)
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
            title = { Text(t(R.string.mig_limpar_a_pasta)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        t(R.string.mig_nada_aqui_e_musica_nem),
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
                                    if (item.isDir) "${tp(R.plurals.n_arquivos, item.files)}, $size" else size,
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
                            t(R.string.mig_nao_mexi_em_tem_musica, scan.foldersWithMusic.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                val selected = scan.candidates.filter { checked[it.name] == true }
                TextButton(enabled = selected.isNotEmpty(), onClick = { onConfirm(selected) }) {
                    Text(t(R.string.mig_mover_para_a_lixeira, selected.size))
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(t(R.string.cancelar)) } },
        )
    }
}
