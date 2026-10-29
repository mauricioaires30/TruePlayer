package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.truetagger.android.GitController
import dev.truetagger.android.GitSetupRequest
import dev.truetagger.android.GitUi
import dev.truetagger.data.GitSummary
import kotlinx.coroutines.delay
import dev.truetagger.android.t
import dev.truetagger.android.tp
import dev.truetagger.android.R

/** Grupo "GitHub" de Configurações: guardar a pasta de músicas num repositório do GitHub. */
@Composable
fun GitSection() {
    val context = LocalContext.current
    val state by GitController.ui.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showCommit by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var showPull by remember { mutableStateOf(false) }

    SettingsGroup("GitHub") {
        // Andamento escondido aparece aqui em qualquer situação (ligar ao GitHub, baixar, enviar...).
        if (state.busy != null && state.progressHidden) {
            BackgroundProgressRow(state)
            SettingsDivider()
        }
        when (state.availability) {
            GitUi.Availability.UNKNOWN, GitUi.Availability.NO_FOLDER -> SettingsRow(
                Icons.Rounded.CloudUpload, t(R.string.git_guardar_no_github), t(R.string.git_escolha_a_pasta_de_musicas), enabled = false,
            )

            GitUi.Availability.UNSUPPORTED -> SettingsRow(
                Icons.Rounded.CloudUpload, t(R.string.git_guardar_no_github), t(R.string.git_so_funciona_com_pasta_na), enabled = false,
            )

            GitUi.Availability.NO_REPO -> SettingsRow(
                Icons.Rounded.CloudUpload, t(R.string.git_ligar_ao_github), t(R.string.git_guarda_musicas_playlists_e_estatisticas),
            ) {
                if (GitController.hasAllFilesAccess()) showSetup = true else GitController.requestAllFilesAccess(context)
            }

            GitUi.Availability.REPO_NEEDS_ACCESS -> SettingsRow(
                Icons.Rounded.Folder, t(R.string.git_liberar_acesso_aos_arquivos), t(R.string.git_o_git_precisa_ler_a),
            ) { GitController.requestAllFilesAccess(context) }

            GitUi.Availability.READY -> {
                val summary = state.summary
                SettingsRow(Icons.Rounded.Sync, repoLabel(state.remote), statusLine(state))
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.Save, t(R.string.git_commitar),
                    if (summary != null && summary.isEmpty) t(R.string.git_nada_novo_para_salvar) else t(R.string.git_salva_o_que_mudou_so),
                    enabled = summary != null && !summary.isEmpty && state.busy == null,
                ) { showCommit = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.CloudUpload, t(R.string.git_enviar), t(R.string.git_manda_os_commits_para_o),
                    enabled = summary != null && state.busy == null,
                ) { showSend = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.CloudDownload, t(R.string.git_baixar_do_github), t(R.string.git_para_restaurar_tudo_num_celular),
                    enabled = state.remote != null && summary != null && state.busy == null,
                ) { showPull = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.SwapHoriz, t(R.string.git_trocar_repositorio), t(R.string.git_usa_outro_repositorio_do_github),
                    enabled = state.busy == null,
                ) { showChange = true }
            }
        }
    }

    if (showSetup) {
        SetupDialog(
            initialUser = state.githubUser,
            onCancel = { showSetup = false },
            onConfirm = { request -> showSetup = false; GitController.setup(context, request) },
        )
    }
    if (showCommit && state.summary != null) {
        CommitDialog(state = state, onClose = { showCommit = false }) { message, name, email ->
            showCommit = false
            GitController.commit(context, message, name, email, push = false, token = "")
        }
    }
    if (showSend && state.summary != null) {
        SendDialog(state = state, onClose = { showSend = false }) { token, remoteUrl ->
            showSend = false
            GitController.push(context, token, remoteUrl)
        }
    }
    if (showPull) {
        AppDialog(
            title = t(R.string.git_baixar_do_github_2),
            onDismiss = { showPull = false },
            confirmText = t(R.string.git_baixar),
            onConfirm = { showPull = false; GitController.pull(context) },
        ) {
            Text(t(R.string.git_traz_o_que_so_existe))
            DialogNote(t(R.string.git_pode_usar_bastante_internet_e))
        }
    }
    if (showChange) {
        ChangeRemoteDialog(
            hasToken = state.hasToken,
            onCancel = { showChange = false },
            onConfirm = { url, token -> showChange = false; GitController.changeRemote(context, url, token) },
        )
    }
}

/**
 * Avisos do Git que valem para o app inteiro: andamento, resultado e a pergunta de substituir o GitHub.
 * Ficam fora da tela de Configurações para não sumirem ao trocar de aba (e para a notificação
 * "abra o app para confirmar" levar direto à pergunta).
 */
@Composable
fun GitDialogs() {
    val state by GitController.ui.collectAsState()

    // Com o andamento na tela, ela fica ligada; escondido, a operação segue no serviço em segundo plano.
    val progressOnScreen = state.busy != null && !state.progressHidden
    val view = LocalView.current
    DisposableEffect(progressOnScreen) {
        view.keepScreenOn = progressOnScreen
        onDispose { view.keepScreenOn = false }
    }
    if (progressOnScreen) ProgressDialog(state)

    state.message?.let { text ->
        AppDialog(title = "GitHub", onDismiss = GitController::dismissMessage) { Text(text) }
    }
    state.replaceBranch?.let { branch ->
        AppDialog(
            title = t(R.string.git_substituir_o_que_esta_no),
            onDismiss = GitController::cancelReplace,
            confirmText = t(R.string.git_substituir),
            onConfirm = GitController::confirmReplace,
            destructive = true,
        ) {
            Text(t(R.string.git_a_branch_do_github_tem_3, branch))
            DialogNote(t(R.string.git_o_que_estava_la_se))
        }
    }
}

/** "usuario/repositorio" a partir do endereço (https, SSH, com usuário embutido, www...). */
private fun repoLabel(remote: String?): String {
    if (remote.isNullOrBlank()) return t(R.string.git_sem_repositorio)
    val https = dev.truetagger.data.GitRemote.stripCredentials(dev.truetagger.data.GitRemote.toHttps(remote))
    val path = https.substringAfter("://", https).substringAfter('/', "").trimEnd('/').removeSuffix(".git")
    val parts = path.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else remote
}

private fun statusLine(state: GitUi): String {
    val summary = state.summary
    val branch = state.branch?.let { t(R.string.git_branch, it) }.orEmpty()
    return when {
        summary == null -> t(R.string.git_lendo_as_mudancas)
        summary.isEmpty -> t(R.string.git_tudo_salvo, branch)
        else -> changesText(summary) + branch
    }
}

/** "3 músicas novas, 1 playlist" — o que mudou desde o último commit, em poucas palavras. */
private fun changesText(summary: GitSummary): String {
    val parts = ArrayList<String>()
    if (summary.songsAdded.isNotEmpty()) parts += tp(R.plurals.git_n_songs_added, summary.songsAdded.size)
    if (summary.songsRemoved.isNotEmpty()) parts += tp(R.plurals.git_n_removed, summary.songsRemoved.size)
    if (summary.tagsEdited.isNotEmpty()) parts += tp(R.plurals.git_n_tags_edited, summary.tagsEdited.size)
    val playlists = summary.playlistsAdded.size + summary.playlistsChanged.size + summary.playlistsRemoved.size
    if (playlists > 0) parts += tp(R.plurals.n_playlists, playlists)
    if (summary.statsChanged) parts += t(R.string.git_stats_word)
    if (summary.settingsChanged) parts += t(R.string.git_settings_word)
    if (parts.isEmpty()) parts += tp(R.plurals.n_arquivos, summary.other)
    return parts.joinToString(", ")
}

/** Andamento escondido: linha com a porcentagem; tocar mostra o andamento completo de novo. */
@Composable
private fun BackgroundProgressRow(state: GitUi) {
    val overall = state.overallFraction
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            Icons.Rounded.CloudUpload,
            state.busyTitle.ifBlank { "Git" } + (overall?.let { " • ${(it * 100).toInt()}%" } ?: ""),
            state.busy,
            trailing = { RowHint("Ver", highlighted = true) },
            onClick = GitController::showProgress,
        )
        if (overall == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 12.dp))
        } else {
            LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

/** Andamento na tela: porcentagem total, etapa, fase atual e tempo. "Ocultar" deixa rodando em segundo plano. */
@Composable
private fun ProgressDialog(state: GitUi) {
    var elapsedS by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.busySinceMs) {
        val start = state.busySinceMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        while (true) {
            elapsedS = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    val overall = state.overallFraction
    AppDialog(
        title = state.busyTitle.ifBlank { "Git" },
        onDismiss = GitController::cancel,
        confirmText = t(R.string.git_ocultar),
        onConfirm = GitController::hideProgress,
        dismissText = t(R.string.cancelar),
        dismissOnOutside = false,
    ) {
        if (state.busySteps > 0) Text(t(R.string.git_etapa_de, state.busyStep, state.busySteps), fontWeight = FontWeight.SemiBold)
        if (overall == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { overall }, modifier = Modifier.weight(1f))
                Text("${(overall * 100).toInt()}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Text(state.busy.orEmpty())
        DialogNote(t(R.string.git_tempo_pode_ocultar_continua_em, elapsedText(elapsedS)))
    }
}

/** "45 s", "2 min 05 s", "1 h 02 min". */
private fun elapsedText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> t(R.string.git_min_s, seconds / 60, (seconds % 60).toString().padStart(2, '0'))
    else -> t(R.string.git_h_min, seconds / 3600, ((seconds % 3600) / 60).toString().padStart(2, '0'))
}

@Composable
private fun ChangeRemoteDialog(hasToken: Boolean, onCancel: () -> Unit, onConfirm: (url: String, token: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AppDialog(
        title = t(R.string.git_trocar_repositorio),
        onDismiss = onCancel,
        confirmText = t(R.string.trocar),
        onConfirm = { onConfirm(url, token) },
        confirmEnabled = url.isNotBlank() && (hasToken || token.isNotBlank()),
    ) {
        DialogNote(t(R.string.git_cole_o_endereco_do_outro))
        OutlinedTextField(url, { url = it.trim() }, label = { Text(t(R.string.git_endereco_do_repositorio)) }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it.trim() },
            label = { Text(if (hasToken) t(R.string.git_token_vazio_usa_o_guardado) else t(R.string.git_token_do_github)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupDialog(initialUser: String, onCancel: () -> Unit, onConfirm: (GitSetupRequest) -> Unit) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(initialUser) }
    var email by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }
    AppDialog(
        title = t(R.string.git_ligar_ao_github),
        onDismiss = onCancel,
        confirmText = t(R.string.git_ligar_e_enviar),
        onConfirm = { onConfirm(GitSetupRequest(url, user, email, branch, token)) },
        confirmEnabled = url.isNotBlank() && user.isNotBlank() && email.isNotBlank() && token.isNotBlank(),
    ) {
        DialogNote(t(R.string.git_pode_ser_um_repositorio_novo))
        OutlinedTextField(url, { url = it.trim() }, label = { Text(t(R.string.git_endereco_do_repositorio)) }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text(t(R.string.git_usuario_do_github)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it.trim() }, label = { Text(t(R.string.git_e_mail_dos_commits)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(branch, { branch = it.trim() }, label = { Text(t(R.string.git_branch_2)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text(t(R.string.git_token_do_github)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        DialogNote(t(R.string.git_token_github_settings_developer_settings))
    }
}

@Composable
private fun CommitDialog(
    state: GitUi,
    onClose: () -> Unit,
    onCommit: (message: String, name: String, email: String) -> Unit,
) {
    val summary = state.summary ?: return
    var message by remember { mutableStateOf(state.suggestedMessage) }
    var name by remember { mutableStateOf(state.userName) }
    var email by remember { mutableStateOf(state.userEmail) }
    AppDialog(
        title = t(R.string.git_commitar),
        onDismiss = onClose,
        confirmText = t(R.string.git_commitar),
        onConfirm = { onCommit(message, name, email) },
        confirmEnabled = !summary.isEmpty && name.isNotBlank() && email.isNotBlank(),
    ) {
        Text(if (summary.isEmpty) t(R.string.git_nada_mudou_desde_o_ultimo) else t(R.string.git_mudou, changesText(summary)))
        OutlinedTextField(message, { message = it }, label = { Text(t(R.string.git_mensagem)) }, modifier = Modifier.fillMaxWidth())
        if (state.userName.isBlank() || state.userEmail.isBlank()) {
            OutlinedTextField(name, { name = it }, label = { Text(t(R.string.git_seu_nome)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim() }, label = { Text(t(R.string.git_seu_e_mail)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        DialogNote(t(R.string.git_fica_salvo_so_neste_celular))
    }
}

@Composable
private fun SendDialog(
    state: GitUi,
    onClose: () -> Unit,
    onSend: (token: String, remoteUrl: String) -> Unit,
) {
    val summary = state.summary ?: return
    var token by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    val needsToken = !state.hasToken
    val needsRemote = state.remote.isNullOrBlank()
    AppDialog(
        title = t(R.string.git_enviar_ao_github),
        onDismiss = onClose,
        confirmText = t(R.string.git_enviar),
        onConfirm = { onSend(token, remoteUrl) },
        confirmEnabled = (!needsToken || token.isNotBlank()) && (!needsRemote || remoteUrl.isNotBlank()),
    ) {
        if (needsRemote) {
            OutlinedTextField(
                remoteUrl, { remoteUrl = it.trim() }, label = { Text(t(R.string.git_endereco_do_repositorio)) },
                placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(t(R.string.git_para, repoLabel(state.remote)))
        }
        if (needsToken) {
            OutlinedTextField(
                token, { token = it.trim() }, label = { Text(t(R.string.git_token_do_github)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!summary.isEmpty) {
            Text(t(R.string.git_ha_mudancas_sem_commit_elas), color = MaterialTheme.colorScheme.tertiary)
        }
        DialogNote(t(R.string.git_roda_em_segundo_plano_se))
    }
}
