package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
    onHideDownload: () -> Unit = {},
) {
    when (state) {
        UpdateUi.Idle -> Unit

        UpdateUi.Checking -> AppDialog(title = "Procurando atualização", onDismiss = onDismiss, dismissText = "Cancelar") {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        is UpdateUi.UpToDate -> AppDialog(title = "Tudo em dia", onDismiss = onDismiss) {
            Text("Você já está na versão mais recente (${state.version}).")
        }

        is UpdateUi.Available -> AppDialog(
            title = "Versão ${state.release.version} disponível",
            onDismiss = onDismiss,
            confirmText = "Atualizar",
            onConfirm = { onUpdate(state.release) },
            dismissText = "Agora não",
        ) {
            ReleaseNotesText(state.release.notes.ifBlank { "Sem notas para esta versão." })
            DialogNote("Download de ${formatSize(state.release.apkSizeBytes)}. Suas músicas e playlists não mudam.")
        }

        // "Ocultar" só esconde: o download continua e o Android pede a confirmação da instalação no fim.
        is UpdateUi.Downloading -> if (!state.hidden) AppDialog(
            title = "Baixando a atualização",
            onDismiss = onHideDownload,
            dismissText = "Ocultar",
            dismissOnOutside = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.weight(1f))
                Text("${state.percent}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            DialogNote("No fim, o Android pede para confirmar a instalação.")
        }

        is UpdateUi.NeedsInstallPermission -> AppDialog(
            title = "Libere a instalação",
            onDismiss = onDismiss,
            confirmText = "Abrir ajustes",
            onConfirm = { onUpdate(state.release) },
            dismissText = "Agora não",
        ) {
            Text("Ative a permissão para o TruePlayer instalar apps e volte aqui.")
        }

        is UpdateUi.Failed -> AppDialog(title = "Atualização", onDismiss = onDismiss) { Text(state.message) }
    }

    if (whatsNew != null && state == UpdateUi.Idle) {
        AppDialog(title = "Novidades da ${whatsNew.first}", onDismiss = onWhatsNewDismiss) {
            ReleaseNotesText(whatsNew.second)
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
