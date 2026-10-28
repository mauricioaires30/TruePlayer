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
    /** Andamento de 0 a 1 da etapa atual; null quando não dá para medir. */
    val busyFraction: Float? = null,
    /** Branch do GitHub que o envio vai substituir; enquanto não for null a tela pergunta se pode. */
    val replaceBranch: String? = null,
    /** Quantos arquivos uma ligação antiga copiou do GitHub para a pasta (0 se não houve). */
    val copiedFromGithub: Int = 0,
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
                    val copied = runCatching { GitManager.copiedFromRemote(dir).size }.getOrDefault(0)
                    result.onSuccess { (summary, who, where) ->
                        _ui.value = base.copy(
                            availability = GitUi.Availability.READY,
                            summary = summary,
                            copiedFromGithub = copied,
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

    /** Mostra "Etapa n de total" e o texto da etapa; [fraction] nulo deixa a barra sem medida. */
    private fun show(n: Int, total: Int, text: String, fraction: Float? = null) {
        _ui.update { it.copy(busy = "Etapa $n de $total\n$text", busyFraction = fraction) }
    }

    /** Acompanha o JGit (contagem e porcentagem) dentro da etapa [n] de [total]. */
    private fun monitor(n: Int, total: Int): GitManager.TextMonitor {
        var fraction: Float? = null
        return GitManager.TextMonitor(onFraction = { fraction = it }) { text -> show(n, total, text, fraction) }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    /** O envio só é possível substituindo a branch do GitHub. */
    private class ReplaceNeeded(val branch: String) : Exception("a branch $branch do GitHub tem outro histórico")

    private var pendingReplace: (() -> Unit)? = null

    /** Como [finish], mas se o envio pede para substituir o GitHub, pergunta e guarda [retry] para depois do sim. */
    private fun finishOrAsk(outcome: Result<String>, app: Context, retry: () -> Unit) {
        val need = outcome.exceptionOrNull() as? ReplaceNeeded
        if (need == null) {
            finish(outcome, app)
            return
        }
        pendingReplace = retry
        _ui.update { it.copy(busy = null, busyFraction = null, replaceBranch = need.branch) }
    }

    /** O usuário aceitou substituir a branch do GitHub: repete o envio já com permissão. */
    fun confirmReplace() {
        val retry = pendingReplace
        pendingReplace = null
        _ui.update { it.copy(replaceBranch = null) }
        retry?.invoke()
    }

    fun cancelReplace() {
        pendingReplace = null
        _ui.update { it.copy(replaceBranch = null, message = "Nada foi enviado. O GitHub continua como estava.") }
    }

    private fun finish(outcome: Result<String>, app: Context) {
        _ui.update { it.copy(busy = null, busyFraction = null, message = outcome.fold({ text -> text }, { e -> "Não deu certo: ${e.message ?: e::class.simpleName}" })) }
        refresh(app)
    }

    /** Salva as capas dos álbuns em `.trueplayer/covers` antes do commit. Falha aqui nunca impede o envio. */
    private fun saveCovers(app: Context, dir: File, step: Int, steps: Int): CoverExport.Result? =
        runCatching {
            CoverExport.export(app, dir, LibraryIndex.all()) { done, total ->
                if (total > 0) show(step, steps, "Salvando capas: $done de $total", done / total.toFloat())
            }
        }.getOrNull()

    private fun coversNote(covers: CoverExport.Result?): String = when {
        covers == null || (covers.written == 0 && covers.missing == 0) -> ""
        covers.missing > 0 -> "\n\n${covers.written} capa(s) salvas. Faltam ${covers.missing}, que vão no próximo envio."
        else -> "\n\n${covers.written} capa(s) salvas em .trueplayer/covers."
    }

    private fun largeFilesNote(large: List<String>) =
        if (large.isEmpty()) "" else "\n\n${large.size} arquivo(s) passam de 100 MB e ficaram de fora (o GitHub recusa): ${large.take(3).joinToString(", ")}."

    // ---- ações

    /**
     * Commit de tudo que mudou. Com [push] também envia ao GitHub. [remoteUrl] só é usado quando o
     * repositório existente ainda não tem `origin`.
     */
    fun commit(context: Context, message: String, name: String, email: String, push: Boolean, token: String, remoteUrl: String = "", replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        val steps = if (push) 3 else 2
        show(1, steps, "Conferindo o que mudou…")
        _ui.update { it.copy(message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                require(name.isNotBlank() && email.isNotBlank()) { "informe nome e e-mail para o commit" }
                prefs(app).edit().putString("name", name).putString("email", email).apply()
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())

                // Antes de registrar qualquer coisa, vê se o envio exigiria substituir a branch do GitHub.
                var sendUser = ""
                var sendSecret = ""
                if (push) {
                    val (user, secret) = credentials(app, dir, token)
                    require(secret.isNotBlank()) { "informe o token do GitHub para enviar" }
                    sendUser = user
                    sendSecret = secret
                    if (!replace && GitManager.sendNeedsReplace(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret)) {
                        throw ReplaceNeeded(GitManager.branch(dir).orEmpty())
                    }
                }

                // Estatísticas e configurações vão no commit: grava a versão mais recente antes de olhar o que mudou.
                runCatching { PlayStatsState.flushNow(app) }
                runCatching { SettingsBackup.writeCurrent(app) }
                val covers = saveCovers(app, dir, 1, steps)

                val changes = GitManager.status(dir)
                // Arquivos grandes demais ficam de fora sem mexer no .gitignore do usuário (lista local em .git/info/exclude).
                val large = GitManager.tooLarge(dir, changes)
                GitManager.addLocalExclude(dir, large)
                show(2, steps, "Registrando ${changes.size} arquivo(s)…")
                GitManager.commitAll(dir, message.ifBlank { "Atualização da pasta" }, name.trim(), email.trim(), monitor(2, steps))

                var pushed = false
                if (push) {
                    if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                    show(3, steps, "Conectando ao GitHub…")
                    GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), sendUser, sendSecret, monitor(3, steps), replace)
                    pushed = true
                }
                (if (pushed) (if (replace) "Commit feito e enviado. O GitHub agora está igual à pasta." else "Commit feito e enviado ao GitHub.") else "Commit feito.") + coversNote(covers) + largeFilesNote(large)
            }
            finishOrAsk(outcome, app) { commit(app, message, name, email, push, token, remoteUrl, replace = true) }
        }
    }

    /** Só envia o que já foi commitado. */
    fun push(context: Context, token: String, remoteUrl: String = "", replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 2, "Conectando ao GitHub…")
        _ui.update { it.copy(message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())
                val (user, secret) = credentials(app, dir, token)
                require(secret.isNotBlank()) { "informe o token do GitHub" }
                if (!replace && GitManager.sendNeedsReplace(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret)) {
                    throw ReplaceNeeded(GitManager.branch(dir).orEmpty())
                }
                if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                show(2, 2, "Enviando…")
                GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, monitor(2, 2), replace)
                if (replace) "Enviado. O GitHub agora está igual à pasta." else "Enviado ao GitHub."
            }
            finishOrAsk(outcome, app) { push(app, token, remoteUrl, replace = true) }
        }
    }

    /**
     * Cria o repositório na pasta, liga ao GitHub, faz o primeiro commit e envia. Nada do GitHub vem para
     * a pasta. Se a branch já existe lá com outro histórico, só substitui depois de o usuário confirmar.
     */
    fun setup(context: Context, request: GitSetupRequest, replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 4, "Conferindo o repositório no GitHub…")
        _ui.update { it.copy(message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("a pasta precisa estar no armazenamento interno do celular")
                require(request.url.startsWith("https://")) { "use o endereço https do repositório (https://github.com/usuario/repo.git)" }
                require(request.token.isNotBlank()) { "informe o token do GitHub" }
                require(request.githubUser.isNotBlank() && request.email.isNotBlank()) { "informe seu usuário e e-mail" }
                val branch = request.branch.ifBlank { "main" }

                // Confere o endereço e o token (sem baixar nada). Branch já existente no GitHub pede confirmação.
                val heads = GitManager.remoteHeads(request.url, request.githubUser, request.token)
                if (branch in heads && !replace) throw ReplaceNeeded(branch)
                runCatching { PlayStatsState.flushNow(app) }
                runCatching { SettingsBackup.writeCurrent(app) }
                val covers = saveCovers(app, dir, 3, 4)

                show(2, 4, "Criando o repositório…")
                GitManager.init(dir, branch, request.url, request.githubUser, request.email)
                prefs(app).edit().putString("github_user", request.githubUser).putString("name", request.githubUser).putString("email", request.email).apply()
                SecretStore.put(app, TOKEN, request.token)

                show(3, 4, "Contando os arquivos da pasta…")
                val changes = GitManager.status(dir)
                val large = GitManager.tooLarge(dir, changes)
                GitManager.updateGitignore(dir, large)
                GitManager.commitAll(dir, "Primeiro commit do TruePlayer", request.githubUser, request.email, monitor(3, 4))
                show(4, 4, "Conectando ao GitHub…")
                GitManager.push(dir, request.url, request.githubUser, request.token, monitor(4, 4), replace = replace && branch in heads)
                (if (replace && branch in heads) "Pasta enviada. O GitHub agora está igual à pasta." else "Repositório criado e enviado ao GitHub.") + coversNote(covers) + largeFilesNote(large)
            }
            finishOrAsk(outcome, app) { setup(app, request, replace = true) }
        }
    }

    /** Aponta a pasta para outro repositório do GitHub. Confere o endereço e o token antes de trocar. */
    fun changeRemote(context: Context, url: String, token: String) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Conferindo o repositório no GitHub…", busyFraction = null, message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                val target = url.trim()
                require(target.startsWith("https://")) { "use o endereço https do repositório (https://github.com/usuario/repo.git)" }
                val user = prefs(app).getString("github_user", "").orEmpty().ifBlank { GitRemote.authUser(target) }
                val secret = token.ifBlank { SecretStore.get(app, TOKEN).orEmpty() }
                require(user.isNotBlank()) { "informe o usuário do GitHub" }
                require(secret.isNotBlank()) { "informe o token do GitHub" }
                // Se o endereço ou o token estiverem errados, isto falha antes de mudar qualquer coisa.
                GitManager.remoteHeads(target, user, secret)
                GitManager.setRemote(dir, target)
                if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                "Repositório trocado para ${GitRemote.stripCredentials(target)}."
            }
            finish(outcome, app)
        }
    }

    /** Tira da pasta os arquivos que a ligação antiga copiou do GitHub (vão para a _lixeira) e limpa o histórico baixado. */
    fun undoGithubCopy(context: Context) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Procurando os arquivos copiados…", busyFraction = null, message = null) }
        scope.launch {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                val files = GitManager.copiedFromRemote(dir)
                require(files.isNotEmpty()) { "não achei arquivos copiados do GitHub" }
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")) + "-github"
                _ui.update { it.copy(busy = "Movendo ${files.size} arquivo(s) para a _lixeira…") }
                val moved = GitManager.moveToTrash(dir, files, stamp)
                val fresh = GitManager.resetToFreshRepo(dir)
                "$moved de ${files.size} arquivo(s) foram para _lixeira/$stamp. " +
                    if (fresh) "A pasta voltou a ser só sua. Use Sincronizar para enviar."
                    else "O histórico local tem commits seus, então foi mantido."
            }
            finish(outcome, app)
        }
    }
}
