package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** Parte de Configurações que cuida do git da pasta de músicas. */
@Composable
fun GitSection() {
    val context = LocalContext.current
    val state by GitController.ui.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var showUndo by remember { mutableStateOf(false) }

    // Mantém a tela ligada enquanto o git trabalha (o primeiro envio pode demorar).
    val view = LocalView.current
    DisposableEffect(state.busy != null) {
        view.keepScreenOn = state.busy != null
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Git", fontWeight = FontWeight.SemiBold)
        Text(
            "Envia a pasta de músicas para um repositório do GitHub.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.availability) {
            GitUi.Availability.UNKNOWN, GitUi.Availability.NO_FOLDER -> Unit

            GitUi.Availability.UNSUPPORTED -> Text(
                "Só funciona com pasta no armazenamento interno do celular.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )

            GitUi.Availability.NO_REPO -> GitAction(
                "Criar repositório Git", Icons.Rounded.CloudUpload,
                "A pasta ainda não tem git. Liga a um repositório do GitHub e envia a pasta.",
            ) {
                if (GitController.hasAllFilesAccess()) showSetup = true else GitController.requestAllFilesAccess(context)
            }

            GitUi.Availability.REPO_NEEDS_ACCESS -> GitAction(
                "Liberar acesso a todos os arquivos", Icons.Rounded.Folder,
                "A pasta já tem git. O app precisa dessa permissão para ver as mudanças.",
            ) { GitController.requestAllFilesAccess(context) }

            GitUi.Availability.READY -> {
                val summary = state.summary
                Text(
                    when {
                        summary == null -> "Lendo as mudanças…"
                        summary.isEmpty -> "Nada mudou desde o último commit."
                        else -> describe(summary)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Repositório da pasta: " + (state.remote ?: "sem endereço de envio (origin)") + (state.branch?.let { " • branch $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GitAction(
                    "Sincronizar com o Git", Icons.Rounded.CloudUpload,
                    "Mostra o que mudou, faz o commit e envia ao GitHub se você quiser.",
                    enabled = summary != null,
                ) { showSync = true }
                GitAction(
                    "Trocar repositório", Icons.Rounded.SwapHoriz,
                    "Aponta a pasta para outro repositório do GitHub.",
                ) { showChange = true }
                if (state.copiedFromGithub > 0) {
                    GitAction(
                        "Tirar arquivos copiados do GitHub (${state.copiedFromGithub})", Icons.Rounded.FolderOff,
                        "Uma ligação antiga trouxe esses arquivos do GitHub para a pasta. Eles vão para a _lixeira.",
                    ) { showUndo = true }
                }
            }
        }
    }

    state.busy?.let { text ->
        MaterialTheme(colorScheme = TruePlayerDarkScheme) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Git") },
                text = {
                    Column {
                        Text(text)
                        Spacer(Modifier.height(12.dp))
                        val fraction = state.busyFraction
                        if (fraction == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
                                Text("${(fraction * 100).toInt()}%", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Não feche o app.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {},
            )
        }
    }

    state.message?.let { text ->
        MaterialTheme(colorScheme = TruePlayerDarkScheme) {
            AlertDialog(
                onDismissRequest = GitController::dismissMessage,
                title = { Text("Git") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(text) } },
                confirmButton = { TextButton(onClick = GitController::dismissMessage) { Text("OK") } },
            )
        }
    }

    if (showSetup) {
        SetupDialog(
            initialUser = state.githubUser,
            onCancel = { showSetup = false },
            onConfirm = { request -> showSetup = false; GitController.setup(context, request) },
        )
    }
    if (showSync && state.summary != null) {
        SyncDialog(state = state, onClose = { showSync = false }) { message, name, email, push, token, remoteUrl ->
            showSync = false
            // Sem mudanças novas, "Enviar" só manda o que já foi commitado.
            if (push && state.summary?.isEmpty == true) {
                GitController.push(context, token, remoteUrl)
            } else {
                GitController.commit(context, message, name, email, push, token, remoteUrl)
            }
        }
    }
    if (showChange) {
        ChangeRemoteDialog(
            hasToken = state.hasToken,
            onCancel = { showChange = false },
            onConfirm = { url, token -> showChange = false; GitController.changeRemote(context, url, token) },
        )
    }
    if (showUndo) {
        MaterialTheme(colorScheme = TruePlayerDarkScheme) {
            AlertDialog(
                onDismissRequest = { showUndo = false },
                title = { Text("Tirar os arquivos copiados?") },
                text = {
                    Text(
                        "${state.copiedFromGithub} arquivo(s) vieram do GitHub para a pasta. Vão para a _lixeira, sem apagar. " +
                            "Suas outras músicas não mudam.",
                    )
                },
                confirmButton = { TextButton(onClick = { showUndo = false; GitController.undoGithubCopy(context) }) { Text("Mover") } },
                dismissButton = { TextButton(onClick = { showUndo = false }) { Text("Cancelar") } },
            )
        }
    }
    state.replaceBranch?.let { branch ->
        MaterialTheme(colorScheme = TruePlayerDarkScheme) {
            AlertDialog(
                onDismissRequest = GitController::cancelReplace,
                title = { Text("Substituir o que está no GitHub?") },
                text = {
                    Text(
                        "A branch $branch do GitHub tem outro histórico. Se continuar, ela fica só com o que está na pasta " +
                            "e o que estava lá se perde. Isso não dá para desfazer.",
                    )
                },
                confirmButton = { TextButton(onClick = GitController::confirmReplace) { Text("Substituir") } },
                dismissButton = { TextButton(onClick = GitController::cancelReplace) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
private fun ChangeRemoteDialog(hasToken: Boolean, onCancel: () -> Unit, onConfirm: (url: String, token: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Trocar repositório") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Cole o endereço do outro repositório. O app confere o endereço e o token antes de trocar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("Endereço do repositório") }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token, { token = it.trim() },
                        label = { Text(if (hasToken) "Token (vazio usa o guardado)" else "Token do GitHub") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank() && (hasToken || token.isNotBlank()),
                    onClick = { onConfirm(url, token) },
                ) { Text("Trocar") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
        )
    }
}

private fun describe(summary: dev.truetagger.data.GitSummary): String {
    val parts = ArrayList<String>()
    if (summary.songsAdded.isNotEmpty()) parts += "${summary.songsAdded.size} música(s) nova(s)"
    if (summary.songsRemoved.isNotEmpty()) parts += "${summary.songsRemoved.size} removida(s)"
    if (summary.playlistsAdded.isNotEmpty() || summary.playlistsChanged.isNotEmpty() || summary.playlistsRemoved.isNotEmpty()) {
        parts += "${summary.playlistsAdded.size + summary.playlistsChanged.size + summary.playlistsRemoved.size} playlist(s) mexida(s)"
    }
    if (summary.tagsEdited.isNotEmpty()) parts += "${summary.tagsEdited.size} tag(s) editada(s)"
    if (summary.statsChanged) parts += "estatísticas atualizadas"
    if (summary.settingsChanged) parts += "configurações atualizadas"
    if (parts.isEmpty()) parts += "${summary.other} arquivo(s) de apoio"
    return "Mudou desde o último commit: " + parts.joinToString(", ") + "."
}

@Composable
private fun GitAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = UiTokens.sectionSpacing)) {
        ActionButton(label, icon, onClick, Modifier.fillMaxWidth(), enabled = enabled)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
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
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Ligar ao GitHub") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Cole o endereço do repositório, novo ou já com arquivos. Nada do GitHub vem para a pasta. Se a branch já existir lá, o app pergunta antes de substituir. O primeiro envio pode ser grande, use Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("Endereço do repositório") }, placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it.trim() }, label = { Text("Usuário do GitHub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it.trim() }, label = { Text("E-mail dos commits") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(branch, { branch = it.trim() }, label = { Text("Branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token, { token = it.trim() }, label = { Text("Token do GitHub") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Crie em Settings > Developer settings > Personal access tokens, com escrita em Contents. Fica cifrado neste celular.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.isNotBlank() && user.isNotBlank() && email.isNotBlank() && token.isNotBlank(),
                    onClick = { onConfirm(GitSetupRequest(url, user, email, branch, token)) },
                ) { Text("Ligar e enviar") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SyncDialog(
    state: GitUi,
    onClose: () -> Unit,
    onCommit: (message: String, name: String, email: String, push: Boolean, token: String, remoteUrl: String) -> Unit,
) {
    val summary = state.summary ?: return
    var message by remember { mutableStateOf(state.suggestedMessage) }
    var name by remember { mutableStateOf(state.userName) }
    var email by remember { mutableStateOf(state.userEmail) }
    var token by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    val needsToken = !state.hasToken
    val needsRemote = state.remote.isNullOrBlank()
    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Sincronizar com o Git") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (summary.isEmpty) "Nada mudou desde o último commit." else describe(summary),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(message, { message = it }, label = { Text("Mensagem do commit") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (needsRemote) {
                        OutlinedTextField(
                            remoteUrl, { remoteUrl = it.trim() }, label = { Text("Endereço do repositório (só para enviar)") },
                            placeholder = { Text("https://github.com/usuario/repo.git") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Usa o repositório que já está na pasta: ${state.remote}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (state.userName.isBlank() || state.userEmail.isBlank()) {
                        OutlinedTextField(name, { name = it }, label = { Text("Seu nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(email, { email = it.trim() }, label = { Text("Seu e-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (needsToken) {
                        OutlinedTextField(
                            token, { token = it.trim() }, label = { Text("Token do GitHub (só para enviar)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Arquivos acima de 100 MB ficam de fora. Se a branch do GitHub tiver outro histórico, o app pergunta antes de substituir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                val identityOk = name.isNotBlank() && email.isNotBlank()
                Column {
                    TextButton(enabled = identityOk && !summary.isEmpty, onClick = { onCommit(message, name, email, false, token, remoteUrl) }) { Text("Só commit") }
                    TextButton(
                        enabled = (summary.isEmpty || identityOk) && (!needsToken || token.isNotBlank()) && (!needsRemote || remoteUrl.isNotBlank()),
                        onClick = { onCommit(message, name, email, true, token, remoteUrl) },
                    ) { Text(if (summary.isEmpty) "Enviar" else "Commit e enviar") }
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        )
    }
}
