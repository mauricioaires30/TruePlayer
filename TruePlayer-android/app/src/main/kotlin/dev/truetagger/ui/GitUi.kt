package dev.truetagger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
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

    // Mantém a tela ligada enquanto o git trabalha (o primeiro envio pode demorar).
    val view = LocalView.current
    DisposableEffect(state.busy != null) {
        view.keepScreenOn = state.busy != null
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Git", fontWeight = FontWeight.SemiBold)
        Text(
            "Guarda sua pasta de músicas num repositório do GitHub: o app mostra o que mudou (músicas novas, playlists, tags, tempo ouvido) e faz o commit pra você.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.availability) {
            GitUi.Availability.UNKNOWN, GitUi.Availability.NO_FOLDER -> Unit

            GitUi.Availability.UNSUPPORTED -> Text(
                "O git só funciona com uma pasta no armazenamento interno do celular. Escolha outra pasta para usar.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )

            GitUi.Availability.NO_REPO -> GitAction(
                "Criar repositório Git", Icons.Rounded.CloudUpload,
                "Sua pasta ainda não tem git. Isso liga a pasta a um repositório vazio do GitHub e envia tudo. O app só lê e envia seus arquivos, nunca apaga.",
            ) {
                if (GitController.hasAllFilesAccess()) showSetup = true else GitController.requestAllFilesAccess(context)
            }

            GitUi.Availability.REPO_NEEDS_ACCESS -> GitAction(
                "Liberar acesso a todos os arquivos", Icons.Rounded.Folder,
                "Sua pasta já tem git. Para ver as mudanças e fazer commit o app precisa trabalhar direto nos arquivos, e o Android pede essa permissão numa tela à parte. Depois é só voltar aqui.",
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
                    "Mostra o que mudou, deixa você ajustar a mensagem e faz o commit. Se quiser, já envia ao GitHub.",
                    enabled = summary != null,
                ) { showSync = true }
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
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        Text("Não feche o app, a tela fica ligada enquanto isso.", style = MaterialTheme.typography.bodySmall)
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
            GitController.commit(context, message, name, email, push, token, remoteUrl)
        }
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
            title = { Text("Criar repositório Git") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Crie antes um repositório VAZIO no GitHub (sem README) e cole o endereço dele abaixo. " +
                            "O primeiro envio leva todas as músicas e pode demorar: prefira o Wi-Fi.",
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
                        "No GitHub: Settings > Developer settings > Personal access tokens. Dê permissão de escrita em Contents do repositório. O token fica guardado cifrado, só neste celular.",
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
                ) { Text("Criar e enviar") }
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
                        "Arquivos acima de 100 MB ficam de fora, porque o GitHub recusa. Nada é enviado à força: se o GitHub tiver algo que você não tem, o envio para e avisa.",
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
                        enabled = identityOk && !summary.isEmpty && (!needsToken || token.isNotBlank()) && (!needsRemote || remoteUrl.isNotBlank()),
                        onClick = { onCommit(message, name, email, true, token, remoteUrl) },
                    ) { Text("Commit e enviar") }
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        )
    }
}
