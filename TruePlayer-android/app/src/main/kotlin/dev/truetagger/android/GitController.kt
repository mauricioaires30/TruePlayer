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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Etapa atual e total de etapas (0 quando a operação não tem etapas). */
    val busyStep: Int = 0,
    val busySteps: Int = 0,
    /** Nome da operação em andamento ("Envio ao GitHub", "Commit"...), para a tela e a notificação. */
    val busyTitle: String = "",
    /** O usuário escondeu o andamento para usar o app; a operação continua em segundo plano. */
    val progressHidden: Boolean = false,
    /** Quando a operação começou (relógio do sistema), para o tempo decorrido não zerar ao reabrir o andamento. */
    val busySinceMs: Long = 0L,
    /** Branch do GitHub que o envio vai substituir; enquanto não for null a tela pergunta se pode. */
    val replaceBranch: String? = null,
    /** Resultado ou erro para mostrar uma vez. */
    val message: String? = null,
) {
    enum class Availability { UNKNOWN, NO_FOLDER, UNSUPPORTED, NO_REPO, REPO_NEEDS_ACCESS, READY }

    /** Andamento da operação inteira (etapas feitas + o quanto da atual já foi), ou null quando não há etapas. */
    val overallFraction: Float?
        get() = if (busySteps > 0) (((busyStep - 1).coerceAtLeast(0) + (busyFraction ?: 0f)) / busySteps).coerceIn(0f, 1f) else null
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

    /** Só uma operação do JGit por vez: ler o status e enviar ao mesmo tempo mexiam nos mesmos buffers internos. */
    private val gitLock = Mutex()

    private fun launchGit(block: suspend () -> Unit) = scope.launch { gitLock.withLock { block() } }

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
        launchGit {
            val tree = MusicFolder.tree(app)
            if (tree == null) {
                _ui.update { it.copy(availability = GitUi.Availability.NO_FOLDER, summary = null) }
                return@launchGit
            }
            val dir = realDir(tree)
            if (dir == null) {
                _ui.update { it.copy(availability = GitUi.Availability.UNSUPPORTED, summary = null) }
                return@launchGit
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

    /** Mostra "Etapa n de total" e o texto da etapa; [fraction] nulo deixa a barra sem medida. */
    private fun show(n: Int, total: Int, text: String, fraction: Float? = null) {
        _ui.update { it.copy(busy = text, busyStep = n, busySteps = total, busyFraction = fraction) }
    }

    /** Acompanha o JGit (contagem e porcentagem) dentro da etapa [n] de [total]. */
    private fun monitor(n: Int, total: Int): GitManager.TextMonitor {
        var fraction: Float? = null
        return GitManager.TextMonitor(onFraction = { fraction = it }, cancelled = { cancelRequested }) { text -> show(n, total, text, fraction) }
    }

    @Volatile private var cancelRequested = false

    /** Pede para parar o que está rodando. O envio só para entre uma etapa e outra; nada fica pela metade no GitHub. */
    fun cancel() {
        if (_ui.value.busy == null) return
        cancelRequested = true
        _ui.update { it.copy(busy = "Cancelando…", busyFraction = null) }
    }

    /** Chamado depois de baixar arquivos do GitHub, para a biblioteca e as playlists lerem a pasta de novo. */
    @Volatile var onFolderChanged: (() -> Unit)? = null

    /** Esconde o andamento para usar o app; a operação segue em segundo plano (com notificação). */
    fun hideProgress() {
        _ui.update { it.copy(progressHidden = true) }
    }

    fun showProgress() {
        _ui.update { it.copy(progressHidden = false) }
    }

    /** Começo de uma operação longa: dá nome a ela e liga o serviço que a mantém viva fora do app. */
    private fun startWork(app: Context, title: String) {
        _ui.update { it.copy(busyTitle = title, progressHidden = false, busySinceMs = System.currentTimeMillis()) }
        GitWorkService.start(app)
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
        cancelRequested = false
        _ui.update { it.copy(busy = null, busyFraction = null, busyStep = 0, busySteps = 0, busyTitle = "", progressHidden = false, busySinceMs = 0L, replaceBranch = need.branch) }
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

    /** Texto do erro com a causa de dentro (o JGit só diz "exception caught during execution of add command"). */
    /** Onde o erro aconteceu (primeiras chamadas do JGit/zip/app no rastro da causa de dentro). */
    private fun whereIs(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return root.stackTrace
            .filter { it.className.startsWith("org.eclipse.jgit") || it.className.startsWith("java.util.zip") || it.className.startsWith("dev.truetagger") }
            .take(4)
            .joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
    }

    private fun saveError(app: Context, error: Throwable) {
        runCatching { File(app.filesDir, "last-git-error.txt").writeText(error.stackTraceToString()) }
    }

    private fun reason(error: Throwable): String {
        if (dev.truetagger.data.GitRetry.isTransient(error)) {
            return "o GitHub ou a conexão falharam mesmo depois de tentar de novo. O que já foi enviado fica salvo e não é reenviado: " +
                "toque em Enviar para continuar."
        }
        if (generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("Cannot lock") }) {
            return "o repositório está travado por uma operação que ficou pela metade (arquivo .git/index.lock). Tente de novo em um minuto."
        }
        val parts = generateSequence(error) { it.cause }
            .take(5)
            .map { it.message?.trim().takeUnless { text -> text.isNullOrBlank() } ?: it::class.simpleName.orEmpty() }
            .distinct()
            .toList()
        return parts.joinToString(" — ")
    }

    private fun skippedNote(skipped: List<String>) =
        if (skipped.isEmpty()) "" else "\n\n${if (skipped.size == 1) "1 arquivo não pôde" else "${skipped.size} arquivos não puderam"} ser lido e ficou de fora: ${skipped.take(3).joinToString(", ")}."

    private fun finish(outcome: Result<String>, app: Context) {
        val cancelled = cancelRequested
        cancelRequested = false
        val text = if (cancelled && outcome.isFailure) "Cancelado."
        else outcome.fold({ ok -> ok }, { e ->
            saveError(app, e)
            val where = whereIs(e)
            "Não deu certo: ${reason(e)}" + if (where.isNotBlank()) "\n\nOnde: $where" else ""
        })
        _ui.update { it.copy(busy = null, busyFraction = null, busyStep = 0, busySteps = 0, busyTitle = "", progressHidden = false, busySinceMs = 0L, message = text) }
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
        covers.missing > 0 -> "\n\n${dev.truetagger.ui.plural(covers.written, "capa salva", "capas salvas")}. Faltam ${covers.missing}, que vão no próximo envio."
        else -> "\n\n${dev.truetagger.ui.plural(covers.written, "capa salva", "capas salvas")} em .trueplayer/covers."
    }

    private fun largeFilesNote(large: List<String>) =
        if (large.isEmpty()) "" else "\n\nAcima de 100 MB (o GitHub recusa), ficaram de fora ${dev.truetagger.ui.plural(large.size, "arquivo", "arquivos")}: ${large.take(3).joinToString(", ")}."

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
        startWork(app, if (push) "Commit e envio" else "Commit")
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                require(name.isNotBlank() && email.isNotBlank()) { "informe nome e e-mail para o commit" }
                GitManager.clearStaleLocks(dir)
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
                show(2, steps, "Registrando ${dev.truetagger.ui.plural(changes.size, "arquivo", "arquivos")}…")
                val skipped = GitManager.commitAll(dir, message.ifBlank { "Atualização da pasta" }, name.trim(), email.trim(), monitor(2, steps))

                var pushed = false
                if (push) {
                    if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                    show(3, steps, "Conectando ao GitHub…")
                    GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), sendUser, sendSecret, monitor(3, steps), replace)
                    pushed = true
                }
                (if (pushed) (if (replace) "Commit feito e enviado. O GitHub agora está igual à pasta." else "Commit feito e enviado ao GitHub.") else "Commit feito.") + skippedNote(skipped) + coversNote(covers) + largeFilesNote(large)
            }
            finishOrAsk(outcome, app) { commit(app, message, name, email, push, token, remoteUrl, replace = true) }
        }
    }

    /** Só envia o que já foi commitado. */
    fun push(context: Context, token: String, remoteUrl: String = "", replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 2, "Conectando ao GitHub…")
        startWork(app, "Envio ao GitHub")
        _ui.update { it.copy(message = null) }
        launchGit {
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
        startWork(app, "Ligando ao GitHub")
        _ui.update { it.copy(message = null) }
        launchGit {
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
                val skipped = GitManager.commitAll(dir, "Primeiro commit do TruePlayer", request.githubUser, request.email, monitor(3, 4))
                show(4, 4, "Conectando ao GitHub…")
                GitManager.push(dir, request.url, request.githubUser, request.token, monitor(4, 4), replace = replace && branch in heads)
                (if (replace && branch in heads) "Pasta enviada. O GitHub agora está igual à pasta." else "Repositório criado e enviado ao GitHub.") + skippedNote(skipped) + coversNote(covers) + largeFilesNote(large)
            }
            finishOrAsk(outcome, app) { setup(app, request, replace = true) }
        }
    }

    /** Aponta a pasta para outro repositório do GitHub. Confere o endereço e o token antes de trocar. */
    fun changeRemote(context: Context, url: String, token: String) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = "Conferindo o repositório no GitHub…", busyFraction = null, message = null) }
        startWork(app, "Trocando repositório")
        launchGit {
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

    /** Baixa do GitHub o que só existe lá (só depois de o usuário confirmar). Nada que ele mudou é sobrescrito. */
    fun pull(context: Context) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 2, "Conectando ao GitHub…")
        startWork(app, "Download do GitHub")
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error("pasta não encontrada")) ?: error("pasta não suportada")
                GitManager.clearStaleLocks(dir)
                val (user, secret) = credentials(app, dir, "")
                require(secret.isNotBlank()) { "informe o token do GitHub (Trocar repositório)" }
                show(2, 2, "Baixando do GitHub…")
                val result = GitManager.pull(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, monitor(2, 2))
                if (result.files.isNotEmpty()) {
                    val audio = result.files
                        .filter { dev.truetagger.data.MigrationPlanner.isAudio(it.substringAfterLast('/')) }
                        .map { File(dir, it).absolutePath }
                    // O sistema só enxerga as músicas novas depois de escanear os arquivos.
                    if (audio.isNotEmpty()) android.media.MediaScannerConnection.scanFile(app, audio.toTypedArray(), null, null)
                    onFolderChanged?.invoke()
                }
                if (result.upToDate) "A pasta já está em dia com o GitHub."
                else "${dev.truetagger.ui.plural(result.files.size, "arquivo baixado", "arquivos baixados")} do GitHub. A biblioteca atualiza sozinha em instantes."
            }
            finish(outcome, app)
        }
    }
}
