package dev.truetagger.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dev.truetagger.data.CommitMessage
import dev.truetagger.data.GitRemote
import dev.truetagger.data.GitSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** Tudo o que a tela de Git precisa saber. */
data class GitUi(
    val availability: Availability = Availability.UNKNOWN,
    val summary: GitSummary? = null,
    /** Endereço do `origin` do repositório que já existe na pasta (sem usuário/senha). */
    val remote: String? = null,
    val branch: String? = null,
    val userName: String = "",
    val userEmail: String = "",
    val githubUser: String = "",
    /** Já há um token guardado ou embutido no endereço do repositório. */
    val hasToken: Boolean = false,
    val suggestedMessage: String = "",
    /** Texto de andamento enquanto algo roda (a interface bloqueia até acabar). */
    val busy: String? = null,
    /** Resultado ou erro para mostrar uma vez. */
    val message: String? = null,
) {
    enum class Availability { UNKNOWN, NO_FOLDER, UNSUPPORTED, NO_REPO, REPO_NEEDS_ACCESS, READY }
}

data class GitSetupRequest(
    val url: String,
    val githubUser: String,
    val email: String,
    val branch: String,
    val token: String,
)

/**
 * Git da pasta de músicas: mostra o que mudou, faz commit e envia ao GitHub. Só aparece quando a
 * pasta tem `.git`; se não tiver, oferece criar o repositório. Quando o `.git` já existe, o app usa
 * o que está nele (endereço `origin`, branch, nome e e-mail) e não altera o `.gitignore` dele.
 * Precisa do acesso a todos os arquivos porque o git trabalha direto com o caminho da pasta.
 */
object GitController {
    private const val PREFS = "truetagger_git"
    private const val TOKEN = "github_token"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow(GitUi())
    val ui: StateFlow<GitUi> = _ui.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- acesso aos arquivos

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun requestAllFilesAccess(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Caminho real da pasta escolhida; só existe para pastas no armazenamento interno (volume "primary"). */
    private fun realDir(tree: SafTree): File? {
        if (!tree.rootDocId.startsWith("primary:")) return null
        val relative = tree.rootDocId.substringAfter(':').trim('/')
        if (relative.isBlank()) return null
        return File(Environment.getExternalStorageDirectory(), relative)
    }

    /** Credenciais para enviar: o que o usuário digitou, senão o que já está no endereço do repositório, senão o token guardado. */
    private fun credentials(app: Context, dir: File, typedToken: String): Pair<String, String> {
        val url = GitManager.remoteUrl(dir).orEmpty()
        val embedded = GitRemote.embeddedCredentials(url)
        val token = typedToken.ifBlank { embedded?.second ?: SecretStore.get(app, TOKEN).orEmpty() }
        val user = embedded?.first ?: prefs(app).getString("github_user", "").orEmpty().ifBlank { GitRemote.authUser(url) }
        return user to token
    }

    // ---- estado

    /** Reconfere a pasta e as mudanças. Barato quando não há repositório. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        scope.launch {
            val tree = MusicFolder.tree(app)
            if (tree == null) {
                _ui.update { it.copy(availability = GitUi.Availability.NO_FOLDER, summary = null) }
                return@launch
            }
            val dir = realDir(tree)
            if (dir == null) {
                _ui.update { it.copy(availability = GitUi.Availability.UNSUPPORTED, summary = null) }
                return@launch
            }
            // A pasta .git é detectada pelo seletor de pastas, mesmo sem o acesso a todos os arquivos.
            val hasGit = runCatching { tree.child(tree.rootDocId, ".git") != null }.getOrDefault(false)
            val saved = prefs(app)
            val base = _ui.value.copy(
                githubUser = saved.getString("github_user", "").orEmpty(),
                hasToken = SecretStore.has(app, TOKEN),
            )
            when {
                !hasGit -> _ui.value = base.copy(availability = GitUi.Availability.NO_REPO, summary = null, remote = null, branch = null)
                !hasAllFilesAccess() -> _ui.value = base.copy(availability = GitUi.Availability.REPO_NEEDS_ACCESS, summary = null)
                else -> {
                    val result = runCatching {
                        val summary = GitSummary.from(GitManager.status(dir))
                        val (name, email) = GitManager.identity(dir)
                        Triple(summary, name to email, GitManager.remoteUrl(dir) to GitManager.branch(dir))
                    }
                    result.onSuccess { (summary, who, where) ->
                        _ui.value = base.copy(
                            availability = GitUi.Availability.READY,
                            summary = summary,
                            remote = where.first?.let(GitRemote::stripCredentials),
                            branch = where.second,
                            hasToken = base.hasToken || where.first?.let { GitRemote.embeddedCredentials(it) } != null,
                            userName = who.first.ifBlank { saved.getString("name", "").orEmpty() },
                            userEmail = who.second.ifBlank { saved.getString("email", "").orEmpty() },
                            suggestedMessage = CommitMessage.build(summary, LocalDate.now(), PlayStatsState.minutesToday(app)),
                        )
                    }.onFailure {
                        _ui.value = base.copy(availability = GitUi.Availability.READY, message = "Não consegui ler o repositório: ${it.message}")
                    }
                }
            }
        }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    private fun finish(outcome: Result<String>, app: Context) {
        _ui.update { it.copy(busy = null, message = outcome.fold({ text -> text }, { e -> "Não deu certo: ${e.message ?: e::class.simpleName}" })) }
        refresh(app)
    }

    private fun largeFilesNote(large: List<String>) =
        if (large.isEmpty()) "" else "\n\n${large.size} arquivo(s) passam de 100 MB e ficaram de fora (o GitHub recusa): ${large.take(3).joinToString(", ")}."

    // ---- ações

    /**
     * Commit de tudo que mudou. Com [push] também envia ao GitHub. [remoteUrl] só é usado quando o
     * repositório existente ainda não tem `origin`.
     */
    fun commit(context: Context, message: String, name: String, email: String, push: Boolean, token: String, remoteUrl: String = "") {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Preparando…", message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                require(name.isNotBlank() && email.isNotBlank()) { "informe nome e e-mail para o commit" }
                prefs(app).edit().putString("name", name).putString("email", email).apply()
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())

                val changes = GitManager.status(dir)
                // Arquivos grandes demais ficam de fora sem mexer no .gitignore do usuário (lista local em .git/info/exclude).
                val large = GitManager.tooLarge(dir, changes)
                GitManager.addLocalExclude(dir, large)
                _ui.update { it.copy(busy = "Registrando as mudanças…") }
                GitManager.commitAll(dir, message.ifBlank { "Atualização da pasta" }, name.trim(), email.trim())

                var pushed = false
                if (push) {
                    val (user, secret) = credentials(app, dir, token)
                    require(secret.isNotBlank()) { "informe o token do GitHub para enviar" }
                    if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                    GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, GitManager.TextMonitor { text -> _ui.update { it.copy(busy = text) } })
                    pushed = true
                }
                (if (pushed) "Commit feito e enviado ao GitHub." else "Commit feito.") + largeFilesNote(large)
            }
            finish(outcome, app)
        }
    }

    /** Só envia o que já foi commitado. */
    fun push(context: Context, token: String, remoteUrl: String = "") {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Conectando ao GitHub…", message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())
                val (user, secret) = credentials(app, dir, token)
                require(secret.isNotBlank()) { "informe o token do GitHub" }
                if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, GitManager.TextMonitor { text -> _ui.update { it.copy(busy = text) } })
                "Enviado ao GitHub."
            }
            finish(outcome, app)
        }
    }

    /** Cria o repositório na pasta, liga ao GitHub, faz o primeiro commit e envia. */
    fun setup(context: Context, request: GitSetupRequest) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Conferindo o repositório no GitHub…", message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("a pasta precisa estar no armazenamento interno do celular")
                require(request.url.startsWith("https://")) { "use o endereço https do repositório (https://github.com/usuario/repo.git)" }
                require(request.token.isNotBlank()) { "informe o token do GitHub" }
                require(request.githubUser.isNotBlank() && request.email.isNotBlank()) { "informe seu usuário e e-mail" }
                val branch = request.branch.ifBlank { "main" }

                // Confere o endereço e o token. O repositório pode estar vazio ou já ter conteúdo.
                val hasContent = GitManager.remoteHasBranches(request.url, request.githubUser, request.token)
                _ui.update { it.copy(busy = "Criando o repositório…") }
                GitManager.init(dir, branch, request.url, request.githubUser, request.email)
                prefs(app).edit().putString("github_user", request.githubUser).putString("name", request.githubUser).putString("email", request.email).apply()
                SecretStore.put(app, TOKEN, request.token)

                val monitor = GitManager.TextMonitor { text -> _ui.update { it.copy(busy = text) } }
                val adopted = if (hasContent) GitManager.adoptRemote(dir, branch, request.githubUser, request.token, monitor) else null

                val changes = GitManager.status(dir)
                val large = GitManager.tooLarge(dir, changes)
                GitManager.updateGitignore(dir, large)
                _ui.update { it.copy(busy = "Registrando os arquivos (pode demorar)…") }
                val firstCommit = if (adopted == null) "Primeiro commit do TruePlayer" else "Pasta de músicas do TruePlayer"
                if (adopted == null || !GitManager.isClean(dir)) {
                    GitManager.commitAll(dir, firstCommit, request.githubUser, request.email)
                }
                GitManager.push(dir, request.url, request.githubUser, request.token, monitor)
                val head = if (adopted == null) "Repositório criado e enviado ao GitHub." else "Pasta ligada ao repositório e enviada ao GitHub."
                val brought = if (adopted != null && adopted.restored > 0) "\n\n${adopted.restored} arquivo(s) que só estavam no GitHub vieram para a pasta." else ""
                head + brought + largeFilesNote(large)
            }
            finish(outcome, app)
        }
    }
}
