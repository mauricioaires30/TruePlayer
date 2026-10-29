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
import dev.truetagger.android.t
import dev.truetagger.android.currentLanguageTag
import dev.truetagger.android.R

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

        UpdateUi.Checking -> AppDialog(title = t(R.string.upd_procurando_atualizacao), onDismiss = onDismiss, dismissText = t(R.string.cancelar)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        is UpdateUi.UpToDate -> AppDialog(title = t(R.string.upd_tudo_em_dia), onDismiss = onDismiss) {
            Text(t(R.string.upd_voce_ja_esta_na_versao, state.version))
        }

        is UpdateUi.Available -> AppDialog(
            title = t(R.string.upd_versao_disponivel, state.release.version),
            onDismiss = onDismiss,
            confirmText = t(R.string.atualizar),
            onConfirm = { onUpdate(state.release) },
            dismissText = t(R.string.mig_agora_nao),
        ) {
            ReleaseNotesText(dev.truetagger.data.ReleaseNotesLocalizer.pick(state.release.notes, currentLanguageTag()).ifBlank { t(R.string.upd_sem_notas_para_esta_versao) })
            DialogNote(t(R.string.upd_download_de_suas_musicas_e, formatSize(state.release.apkSizeBytes)))
        }

        // "Ocultar" só esconde: o download continua e o Android pede a confirmação da instalação no fim.
        is UpdateUi.Downloading -> if (!state.hidden) AppDialog(
            title = t(R.string.upd_baixando_a_atualizacao),
            onDismiss = onHideDownload,
            dismissText = t(R.string.git_ocultar),
            dismissOnOutside = false,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.weight(1f))
                Text("${state.percent}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
            DialogNote(t(R.string.upd_no_fim_o_android_pede))
        }

        is UpdateUi.NeedsInstallPermission -> AppDialog(
            title = t(R.string.upd_libere_a_instalacao),
            onDismiss = onDismiss,
            confirmText = t(R.string.upd_abrir_ajustes),
            onConfirm = { onUpdate(state.release) },
            dismissText = t(R.string.mig_agora_nao),
        ) {
            Text(t(R.string.upd_ative_a_permissao_para_o))
        }

        is UpdateUi.Failed -> AppDialog(title = t(R.string.upd_atualizacao), onDismiss = onDismiss) { Text(state.message) }
    }

    if (whatsNew != null && state == UpdateUi.Idle) {
        AppDialog(title = t(R.string.upd_novidades_da, whatsNew.first), onDismiss = onWhatsNewDismiss) {
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
    if (bytes <= 0L) t(R.string.few_mb) else "%.0f MB".format(bytes / 1_048_576.0)
