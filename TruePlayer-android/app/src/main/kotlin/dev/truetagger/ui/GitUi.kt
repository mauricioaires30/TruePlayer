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
                Icons.Rounded.CloudUpload, "Guardar no GitHub", "Escolha a pasta de músicas primeiro", enabled = false,
            )

            GitUi.Availability.UNSUPPORTED -> SettingsRow(
                Icons.Rounded.CloudUpload, "Guardar no GitHub", "Só funciona com pasta na memória interna do celular", enabled = false,
            )

            GitUi.Availability.NO_REPO -> SettingsRow(
                Icons.Rounded.CloudUpload, "Ligar ao GitHub", "Guarda músicas, playlists e estatísticas num repositório seu",
            ) {
                if (GitController.hasAllFilesAccess()) showSetup = true else GitController.requestAllFilesAccess(context)
            }

            GitUi.Availability.REPO_NEEDS_ACCESS -> SettingsRow(
                Icons.Rounded.Folder, "Liberar acesso aos arquivos", "O Git precisa ler a pasta direto",
            ) { GitController.requestAllFilesAccess(context) }

            GitUi.Availability.READY -> {
                val summary = state.summary
                SettingsRow(Icons.Rounded.Sync, repoLabel(state.remote), statusLine(state))
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.Save, "Commitar",
                    if (summary != null && summary.isEmpty) "Nada novo para salvar" else "Salva o que mudou, só neste celular",
                    enabled = summary != null && !summary.isEmpty && state.busy == null,
                ) { showCommit = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.CloudUpload, "Enviar", "Manda os commits para o GitHub",
                    enabled = summary != null && state.busy == null,
                ) { showSend = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.CloudDownload, "Baixar do GitHub", "Para restaurar tudo num celular novo",
                    enabled = state.remote != null && summary != null && state.busy == null,
                ) { showPull = true }
                SettingsDivider()
                SettingsRow(
                    Icons.Rounded.SwapHoriz, "Trocar repositório", "Usa outro repositório do GitHub",
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
            title = "Baixar do GitHub?",
            onDismiss = { showPull = false },
            confirmText = "Baixar",
            onConfirm = { showPull = false; GitController.pull(context) },
        ) {
            Text("Traz o que só existe no GitHub e atualiza o que você não mexeu. O que você mudou continua como está.")
            DialogNote("Pode usar bastante internet e espaço.")
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
            title = "Substituir o que está no GitHub?",
            onDismiss = GitController::cancelReplace,
            confirmText = "Substituir",
            onConfirm = GitController::confirmReplace,
            destructive = true,
        ) {
            Text("A branch $branch do GitHub tem outro histórico. Se continuar, ela fica só com o que está na pasta.")
            DialogNote("O que estava lá se perde. Isso não dá para desfazer.")
        }
    }
}

/** "usuario/repositorio" a partir do endereço (https, SSH, com usuário embutido, www...). */
private fun repoLabel(remote: String?): String {
    if (remote.isNullOrBlank()) return "Sem repositório"
    val https = dev.truetagger.data.GitRemote.stripCredentials(dev.truetagger.data.GitRemote.toHttps(remote))
    val path = https.substringAfter("://", https).substringAfter('/', "").trimEnd('/').removeSuffix(".git")
    val parts = path.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else remote
}

private fun statusLine(state: GitUi): String {
    val summary = state.summary
    val branch = state.branch?.let { " • branch $it" }.orEmpty()
    return when {
        summary == null -> "Lendo as mudanças…"
        summary.isEmpty -> "Tudo salvo$branch"
        else -> changesText(summary) + branch
    }
}

/** "3 músicas novas, 1 playlist" — o que mudou desde o último commit, em poucas palavras. */
private fun changesText(summary: GitSummary): String {
    fun count(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many
    val parts = ArrayList<String>()
    if (summary.songsAdded.isNotEmpty()) parts += count(summary.songsAdded.size, "música nova", "músicas novas")
    if (summary.songsRemoved.isNotEmpty()) parts += count(summary.songsRemoved.size, "removida", "removidas")
    if (summary.tagsEdited.isNotEmpty()) parts += count(summary.tagsEdited.size, "tag editada", "tags editadas")
    val playlists = summary.playlistsAdded.size + summary.playlistsChanged.size + summary.playlistsRemoved.size
    if (playlists > 0) parts += count(playlists, "playlist", "playlists")
    if (summary.statsChanged) parts += "estatísticas"
    if (summary.settingsChanged) parts += "configurações"
    if (parts.isEmpty()) parts += count(summary.other, "arquivo", "arquivos")
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
        confirmText = "Ocultar",
        onConfirm = GitController::hideProgress,
        dismissText = "Cancelar",
        dismissOnOutside = false,
    ) {
        if (state.busySteps > 0) Text("Etapa ${state.busyStep} de ${state.busySteps}", fontWeight = FontWeight.SemiBold)
        if (overall == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { overall }, modifier = Modifier.weight(1f))
                Text("${(overall * 100).toInt()}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Text(state.busy.orEmpty())
        DialogNote("Tempo: ${elapsedText(elapsedS)}. Pode ocultar: continua em segundo plano, com o andamento na notificação.")
    }
}

/** "45 s", "2 min 05 s", "1 h 02 min". */
private fun elapsedText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"
    else -> "${seconds / 3600} h ${((seconds % 3600) / 60).toString().padStart(2, '0')} min"
}

@Composable
private fun ChangeRemoteDialog(hasToken: Boolean, onCancel: () -> Unit, onConfirm: (url: String, token: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AppDialog(
        title = "Trocar repositório",
        onDismiss = onCancel,
        confirmText = "Trocar",
        onConfirm = { onConfirm(url, token) },
        confirmEnabled = url.isNotBlank() && (hasToken || token.isNotBlank()),
    ) {
        DialogNote("Cole o endereço do outro repositório. O app confere antes de trocar.")
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Endereço do repositório") }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it.trim() },
            label = { Text(if (hasToken) "Token (vazio usa o guardado)" else "Token do GitHub") },
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
        title = "Ligar ao GitHub",
        onDismiss = onCancel,
        confirmText = "Ligar e enviar",
        onConfirm = { onConfirm(GitSetupRequest(url, user, email, branch, token)) },
        confirmEnabled = url.isNotBlank() && user.isNotBlank() && email.isNotBlank() && token.isNotBlank(),
    ) {
        DialogNote("Pode ser um repositório novo ou que já tenha arquivos. Nada do GitHub vem para a pasta.")
        OutlinedTextField(url, { url = it.trim() }, label = { Text("Endereço do repositório") }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text("Usuário do GitHub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it.trim() }, label = { Text("E-mail dos commits") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(branch, { branch = it.trim() }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text("Token do GitHub") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        DialogNote("Token: GitHub > Settings > Developer settings > Personal access tokens, com escrita em Contents. Fica cifrado neste celular. O primeiro envio é grande: use Wi-Fi.")
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
        title = "Commitar",
        onDismiss = onClose,
        confirmText = "Commitar",
        onConfirm = { onCommit(message, name, email) },
        confirmEnabled = !summary.isEmpty && name.isNotBlank() && email.isNotBlank(),
    ) {
        Text(if (summary.isEmpty) "Nada mudou desde o último commit." else "Mudou: ${changesText(summary)}.")
        OutlinedTextField(message, { message = it }, label = { Text("Mensagem") }, modifier = Modifier.fillMaxWidth())
        if (state.userName.isBlank() || state.userEmail.isBlank()) {
            OutlinedTextField(name, { name = it }, label = { Text("Seu nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Seu e-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        DialogNote("Fica salvo só neste celular até você tocar em Enviar. Arquivos acima de 100 MB ficam de fora.")
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
        title = "Enviar ao GitHub",
        onDismiss = onClose,
        confirmText = "Enviar",
        onConfirm = { onSend(token, remoteUrl) },
        confirmEnabled = (!needsToken || token.isNotBlank()) && (!needsRemote || remoteUrl.isNotBlank()),
    ) {
        if (needsRemote) {
            OutlinedTextField(
                remoteUrl, { remoteUrl = it.trim() }, label = { Text("Endereço do repositório") },
                placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text("Para: ${repoLabel(state.remote)}")
        }
        if (needsToken) {
            OutlinedTextField(
                token, { token = it.trim() }, label = { Text("Token do GitHub") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!summary.isEmpty) {
            Text("Há mudanças sem commit. Elas não vão: faça o commit antes.", color = MaterialTheme.colorScheme.tertiary)
        }
        DialogNote("Roda em segundo plano. Se falhar, o próximo envio continua de onde parou.")
    }
}
