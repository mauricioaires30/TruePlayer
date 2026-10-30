package dev.truetagger.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dev.truetagger.data.CommitMessage
import dev.truetagger.data.CommitText
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
    /** A última operação terminou com erro (para a notificação usar o ícone de erro). */
    val failed: Boolean = false,
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
                            suggestedMessage = CommitMessage.build(summary, LocalDate.now(), PlayStatsState.minutesToday(app), AppCommitText),
                        )
                    }.onFailure {
                        _ui.value = base.copy(availability = GitUi.Availability.READY, message = t(R.string.git_nao_consegui_ler_o_repositorio, it.message))
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
        _ui.update { it.copy(busy = t(R.string.git_cancelando), busyFraction = null) }
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
    private class ReplaceNeeded(val branch: String) : Exception(t(R.string.git_a_branch_do_github_tem, branch))

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
        _ui.update { it.copy(replaceBranch = null, message = t(R.string.git_nada_foi_enviado_o_github)) }
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
            return t(R.string.git_err_transient)
        }
        if (generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("Cannot lock") }) {
            return t(R.string.git_o_repositorio_esta_travado_por)
        }
        val parts = generateSequence(error) { it.cause }
            .take(5)
            .map { it.message?.trim().takeUnless { text -> text.isNullOrBlank() } ?: it::class.simpleName.orEmpty() }
            .distinct()
            .toList()
        return parts.joinToString(" — ")
    }

    private fun skippedNote(skipped: List<String>) =
        if (skipped.isEmpty()) "" else "\n\n" + tp(R.plurals.git_skipped_files, skipped.size, skipped.take(3).joinToString(", "))

    private fun finish(outcome: Result<String>, app: Context) {
        val cancelled = cancelRequested
        cancelRequested = false
        val text = if (cancelled && outcome.isFailure) t(R.string.git_cancelado)
        else outcome.fold({ ok -> ok }, { e ->
            saveError(app, e)
            val where = whereIs(e)
            t(R.string.git_nao_deu_certo, reason(e)) + if (where.isNotBlank()) t(R.string.git_onde, where) else ""
        })
        _ui.update { it.copy(busy = null, busyFraction = null, busyStep = 0, busySteps = 0, busyTitle = "", progressHidden = false, busySinceMs = 0L, message = text, failed = outcome.isFailure && !cancelled) }
        refresh(app)
    }

    /** Salva as capas dos álbuns em `.trueplayer/covers` antes do commit. Falha aqui nunca impede o envio. */
    private fun saveCovers(app: Context, dir: File, step: Int, steps: Int): CoverExport.Result? =
        runCatching {
            CoverExport.export(app, dir, LibraryIndex.all()) { done, total ->
                if (total > 0) show(step, steps, t(R.string.git_salvando_capas_de, done, total), done / total.toFloat())
            }
        }.getOrNull()

    private fun coversNote(covers: CoverExport.Result?): String = when {
        covers == null || (covers.written == 0 && covers.missing == 0) -> ""
        covers.missing > 0 -> t(R.string.git_faltam_que_vao_no_proximo, tp(R.plurals.n_capas_salvas, covers.written), covers.missing)
        else -> t(R.string.git_em_trueplayer_covers, tp(R.plurals.n_capas_salvas, covers.written))
    }

    /** "4 s", "2 min 05 s". */
    private fun elapsed(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${(seconds % 60).toString().padStart(2, '0')} s"
    }

    private fun largeFilesNote(large: List<String>) =
        if (large.isEmpty()) "" else t(R.string.git_acima_de_100_mb_o, tp(R.plurals.n_arquivos, large.size), large.take(3).joinToString(", "))

    // ---- ações

    /**
     * Commit de tudo que mudou. Com [push] também envia ao GitHub. [remoteUrl] só é usado quando o
     * repositório existente ainda não tem `origin`.
     */
    fun commit(context: Context, message: String, name: String, email: String, push: Boolean, token: String, remoteUrl: String = "", replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        val steps = if (push) 3 else 2
        show(1, steps, t(R.string.git_conferindo_o_que_mudou))
        startWork(app, if (push) t(R.string.git_commit_e_envio) else t(R.string.git_commit))
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error(t(R.string.git_pasta_nao_encontrada))) ?: error(t(R.string.git_pasta_nao_suportada))
                require(name.isNotBlank() && email.isNotBlank()) { t(R.string.git_informe_nome_e_e_mail) }
                GitManager.clearStaleLocks(dir)
                prefs(app).edit().putString("name", name).putString("email", email).apply()
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())

                // Antes de registrar qualquer coisa, vê se o envio exigiria substituir a branch do GitHub.
                var sendUser = ""
                var sendSecret = ""
                if (push) {
                    val (user, secret) = credentials(app, dir, token)
                    require(secret.isNotBlank()) { t(R.string.git_informe_o_token_do_github) }
                    sendUser = user
                    sendSecret = secret
                    if (!replace && GitManager.sendNeedsReplace(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret)) {
                        throw ReplaceNeeded(GitManager.branch(dir).orEmpty())
                    }
                }

                // Tempo de cada etapa, mostrado no fim: se algo ficar lento, dá para ver onde.
                val startedAt = android.os.SystemClock.elapsedRealtime()
                // Estatísticas e configurações vão no commit: grava a versão mais recente antes de olhar o que mudou.
                runCatching { PlayStatsState.flushNow(app) }
                runCatching { SettingsBackup.writeCurrent(app) }
                val covers = saveCovers(app, dir, 1, steps)

                val changes = GitManager.status(dir)
                // Arquivos grandes demais ficam de fora sem mexer no .gitignore do usuário (lista local em .git/info/exclude).
                val large = GitManager.tooLarge(dir, changes)
                GitManager.addLocalExclude(dir, large)
                val preparedAt = android.os.SystemClock.elapsedRealtime()
                show(2, steps, t(R.string.git_registrando, tp(R.plurals.n_arquivos, changes.size)))
                val skipped = GitManager.commitAll(dir, message.ifBlank { t(R.string.git_atualizacao_da_pasta) }, name.trim(), email.trim(), monitor(2, steps), known = changes)
                val committedAt = android.os.SystemClock.elapsedRealtime()

                var pushed = false
                if (push) {
                    if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                    show(3, steps, t(R.string.git_conectando_ao_github))
                    GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), sendUser, sendSecret, monitor(3, steps), replace)
                    pushed = true
                }
                val doneAt = android.os.SystemClock.elapsedRealtime()
                val times = t(R.string.git_tempos, elapsed(preparedAt - startedAt), elapsed(committedAt - preparedAt), if (pushed) elapsed(doneAt - committedAt) else "—")
                (if (pushed) (if (replace) t(R.string.git_commit_feito_e_enviado_o) else t(R.string.git_commit_feito_e_enviado_ao)) else t(R.string.git_commit_feito)) + skippedNote(skipped) + coversNote(covers) + largeFilesNote(large) + times
            }
            finishOrAsk(outcome, app) { commit(app, message, name, email, push, token, remoteUrl, replace = true) }
        }
    }

    /** Só envia o que já foi commitado. */
    fun push(context: Context, token: String, remoteUrl: String = "", replace: Boolean = false) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 2, t(R.string.git_conectando_ao_github))
        startWork(app, t(R.string.git_envio_ao_github))
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error(t(R.string.git_pasta_nao_encontrada))) ?: error(t(R.string.git_pasta_nao_suportada))
                if (remoteUrl.isNotBlank() && GitManager.remoteUrl(dir).isNullOrBlank()) GitManager.setRemote(dir, remoteUrl.trim())
                val (user, secret) = credentials(app, dir, token)
                require(secret.isNotBlank()) { t(R.string.git_informe_o_token_do_github_2) }
                if (!replace && GitManager.sendNeedsReplace(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret)) {
                    throw ReplaceNeeded(GitManager.branch(dir).orEmpty())
                }
                if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                show(2, 2, t(R.string.git_enviando))
                GitManager.push(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, monitor(2, 2), replace)
                if (replace) t(R.string.git_enviado_o_github_agora_esta) else t(R.string.git_enviado_ao_github)
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
        show(1, 4, t(R.string.git_conferindo_o_repositorio_no_github))
        startWork(app, t(R.string.git_ligando_ao_github))
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error(t(R.string.git_pasta_nao_encontrada))) ?: error(t(R.string.git_a_pasta_precisa_estar_no))
                require(request.url.startsWith("https://")) { t(R.string.git_use_o_endereco_https_do) }
                require(request.token.isNotBlank()) { t(R.string.git_informe_o_token_do_github_2) }
                require(request.githubUser.isNotBlank() && request.email.isNotBlank()) { t(R.string.git_informe_seu_usuario_e_e) }
                val branch = request.branch.ifBlank { "main" }

                // Confere o endereço e o token (sem baixar nada). Branch já existente no GitHub pede confirmação.
                val heads = GitManager.remoteHeads(request.url, request.githubUser, request.token)
                if (branch in heads && !replace) throw ReplaceNeeded(branch)
                runCatching { PlayStatsState.flushNow(app) }
                runCatching { SettingsBackup.writeCurrent(app) }
                val covers = saveCovers(app, dir, 3, 4)

                show(2, 4, t(R.string.git_criando_o_repositorio))
                GitManager.init(dir, branch, request.url, request.githubUser, request.email)
                prefs(app).edit().putString("github_user", request.githubUser).putString("name", request.githubUser).putString("email", request.email).apply()
                SecretStore.put(app, TOKEN, request.token)

                show(3, 4, t(R.string.git_contando_os_arquivos_da_pasta))
                val changes = GitManager.status(dir)
                val large = GitManager.tooLarge(dir, changes)
                GitManager.updateGitignore(dir, large)
                val skipped = GitManager.commitAll(dir, t(R.string.git_primeiro_commit_do_trueplayer), request.githubUser, request.email, monitor(3, 4))
                show(4, 4, t(R.string.git_conectando_ao_github))
                GitManager.push(dir, request.url, request.githubUser, request.token, monitor(4, 4), replace = replace && branch in heads)
                (if (replace && branch in heads) t(R.string.git_pasta_enviada_o_github_agora) else t(R.string.git_repositorio_criado_e_enviado_ao)) + skippedNote(skipped) + coversNote(covers) + largeFilesNote(large)
            }
            finishOrAsk(outcome, app) { setup(app, request, replace = true) }
        }
    }

    /** Aponta a pasta para outro repositório do GitHub. Confere o endereço e o token antes de trocar. */
    fun changeRemote(context: Context, url: String, token: String) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        _ui.update { it.copy(busy = t(R.string.git_conferindo_o_repositorio_no_github), busyFraction = null, message = null) }
        startWork(app, t(R.string.git_trocando_repositorio))
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error(t(R.string.git_pasta_nao_encontrada))) ?: error(t(R.string.git_pasta_nao_suportada))
                val target = url.trim()
                require(target.startsWith("https://")) { t(R.string.git_use_o_endereco_https_do) }
                val user = prefs(app).getString("github_user", "").orEmpty().ifBlank { GitRemote.authUser(target) }
                val secret = token.ifBlank { SecretStore.get(app, TOKEN).orEmpty() }
                require(user.isNotBlank()) { t(R.string.git_informe_o_usuario_do_github) }
                require(secret.isNotBlank()) { t(R.string.git_informe_o_token_do_github_2) }
                // Se o endereço ou o token estiverem errados, isto falha antes de mudar qualquer coisa.
                GitManager.remoteHeads(target, user, secret)
                GitManager.setRemote(dir, target)
                if (token.isNotBlank()) SecretStore.put(app, TOKEN, token)
                t(R.string.git_repositorio_trocado_para, GitRemote.stripCredentials(target))
            }
            finish(outcome, app)
        }
    }

    /** Baixa do GitHub o que só existe lá (só depois de o usuário confirmar). Nada que ele mudou é sobrescrito. */
    fun pull(context: Context) {
        val app = context.applicationContext
        if (_ui.value.busy != null) return
        show(1, 2, t(R.string.git_conectando_ao_github))
        startWork(app, t(R.string.git_download_do_github))
        _ui.update { it.copy(message = null) }
        launchGit {
            val outcome = runCatching {
                val dir = realDir(MusicFolder.tree(app) ?: error(t(R.string.git_pasta_nao_encontrada))) ?: error(t(R.string.git_pasta_nao_suportada))
                GitManager.clearStaleLocks(dir)
                val (user, secret) = credentials(app, dir, "")
                require(secret.isNotBlank()) { t(R.string.git_informe_o_token_do_github_3) }
                show(2, 2, t(R.string.git_baixando_do_github))
                val result = GitManager.pull(dir, GitManager.remoteUrl(dir).orEmpty(), user, secret, monitor(2, 2))
                if (result.files.isNotEmpty()) {
                    val audio = result.files
                        .filter { dev.truetagger.data.MigrationPlanner.isAudio(it.substringAfterLast('/')) }
                        .map { File(dir, it).absolutePath }
                    // O sistema só enxerga as músicas novas depois de escanear os arquivos.
                    if (audio.isNotEmpty()) android.media.MediaScannerConnection.scanFile(app, audio.toTypedArray(), null, null)
                    onFolderChanged?.invoke()
                }
                if (result.upToDate) t(R.string.git_a_pasta_ja_esta_em)
                else t(R.string.git_do_github_a_biblioteca_atualiza, tp(R.plurals.n_arquivos_baixados, result.files.size))
            }
            finish(outcome, app)
        }
    }
}

/** Frases do commit sugerido no idioma do app. */
private object AppCommitText : CommitText {
    override fun songsAdded(n: Int) = tp(R.plurals.commit_songs_added, n)
    override fun songsRemoved(n: Int) = tp(R.plurals.commit_songs_removed, n)
    override fun playlistsAdded(n: Int) = tp(R.plurals.commit_playlists_added, n)
    override fun playlistsChanged(n: Int) = tp(R.plurals.commit_playlists_changed, n)
    override fun playlistsRemoved(n: Int) = tp(R.plurals.commit_playlists_removed, n)
    override fun tagsEdited(n: Int) = tp(R.plurals.commit_tags_edited, n)
    override fun settingsUpdated() = t(R.string.commit_settings)
    override fun minutesListened(minutes: Long) = t(R.string.commit_minutes, minutes)
    override fun folderUpdate() = t(R.string.commit_folder_update)
    override fun andMore(n: Int) = t(R.string.commit_and_more, n)
}
