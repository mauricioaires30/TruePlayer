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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.truetagger.android.UpdateUi
import dev.truetagger.data.MdKind
import dev.truetagger.data.MdLine
import dev.truetagger.data.ReleaseInfo
import dev.truetagger.data.SimpleMarkdown

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
                        ReleaseNotesText(state.release.notes.ifBlank { "Sem notas para esta versão." })
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Download de ${formatSize(state.release.apkSizeBytes)}. Suas músicas e playlists não mudam.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { onUpdate(state.release) }) { Text("Atualizar") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Agora não") } },
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
                            "No fim, o Android pede para confirmar a instalação.",
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
                        "Ative a permissão para o TruePlayer instalar apps e volte aqui.",
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
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { ReleaseNotesText(whatsNew.second) } },
                confirmButton = { TextButton(onClick = onWhatsNewDismiss) { Text("OK") } },
            )
        }
    }
}

/** Mostra as notas da release (Markdown) como texto formatado: negrito, listas e títulos, sem os símbolos. */
@Composable
private fun ReleaseNotesText(markdown: String) {
    val lines = SimpleMarkdown.parse(markdown, dropLeadingHeading = true)
    Column {
        lines.forEach { line ->
            when (line.kind) {
                MdKind.BLANK -> Spacer(Modifier.height(6.dp))
                MdKind.BULLET -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    Text(styled(line), style = MaterialTheme.typography.bodySmall)
                }
                MdKind.HEADING -> Text(styled(line), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                MdKind.TEXT -> Text(styled(line), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun styled(line: MdLine): AnnotatedString = buildAnnotatedString {
    line.spans.forEach { span ->
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
        )
        withStyle(style) { append(span.text) }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes <= 0L) "alguns MB" else "%.0f MB".format(bytes / 1_048_576.0)
