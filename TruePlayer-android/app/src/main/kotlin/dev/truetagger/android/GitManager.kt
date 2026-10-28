package dev.truetagger.android

import dev.truetagger.data.GitChange
import dev.truetagger.data.GitLimits
import dev.truetagger.data.GitRemote
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Operações de git (JGit) na pasta de músicas. Todas bloqueiam: chame fora da thread principal. */
internal object GitManager {
    /** Mostra em português o andamento que o JGit informa em inglês. */
    class TextMonitor(
        private val onFraction: (Float?) -> Unit = {},
        private val onText: (String) -> Unit,
    ) : ProgressMonitor {
        private var title = ""
        private var total = 0
        private var done = 0
        private var lastPercent = -1

        override fun start(totalTasks: Int) {}

        override fun beginTask(title: String, totalWork: Int) {
            this.title = translate(title)
            total = totalWork
            done = 0
            lastPercent = -1
            onFraction(if (totalWork > 0) 0f else null)
            onText(if (totalWork > 0) "${this.title}: 0 de $totalWork (0%)" else this.title)
        }

        override fun update(completed: Int) {
            done += completed
            if (total <= 0) return
            val percent = (done * 100L / total).coerceAtMost(100).toInt()
            // Só avisa quando a porcentagem muda, para não atualizar a tela a cada arquivo.
            if (percent == lastPercent) return
            lastPercent = percent
            onFraction(percent / 100f)
            onText("$title: ${done.coerceAtMost(total)} de $total ($percent%)")
        }

        override fun endTask() {}
        override fun isCancelled(): Boolean = false
        override fun showDuration(enabled: Boolean) {}

        private fun translate(text: String) = when {
            text.contains("Counting", true) -> "Contando arquivos"
            text.contains("Compressing", true) -> "Compactando"
            text.contains("Writing", true) -> "Enviando"
            text.contains("Finding sources", true) -> "Preparando"
            text.contains("Receiving", true) -> "Baixando do GitHub"
            text.contains("Resolving", true) -> "Montando o histórico"
            text.contains("Checking out", true) -> "Atualizando a pasta"
            else -> text
        }
    }

    init {
        // O Android dá ~256 MB ao app. Com o padrão do JGit, uma música de 40 MB era lida inteira para a
        // memória (e comprimida), e algumas músicas seguidas estouravam o limite. Aqui os arquivos grandes
        // passam em fluxo e as janelas de leitura ficam pequenas.
        runCatching {
            val cache = WindowCacheConfig()
            cache.packedGitLimit = 8L shl 20
            cache.packedGitWindowSize = 1 shl 20
            cache.setPackedGitMMAP(false)
            cache.deltaBaseCacheLimit = 2 shl 20
            cache.streamFileThreshold = 1 shl 20
            @Suppress("DEPRECATION")
            cache.install()
        }
    }

    /**
     * Abre o repositório para operações pesadas (status, commit, envio) com limites de memória só nesta
     * sessão. Não use para quem grava a configuração: estes ajustes não devem ir para o `.git/config`.
     */
    private fun openRepo(dir: File): Git = Git.open(dir).also { git ->
        val config = git.repository.config
        config.setLong("core", null, "bigFileThreshold", 1L shl 20)
        config.setInt("pack", null, "threads", 1)
    }

    fun hasRepo(dir: File) = File(dir, ".git").exists()

    fun status(dir: File): List<GitChange> = openRepo(dir).use { git ->
        val st = git.status().call()
        val out = ArrayList<GitChange>()
        st.added.forEach { out += GitChange(it, GitChange.Kind.ADDED) }
        st.untracked.forEach { out += GitChange(it, GitChange.Kind.ADDED) }
        // Pasta inteira nova: lista os arquivos de dentro, não só a pasta.
        st.untrackedFolders.forEach { folder ->
            File(dir, folder).walkTopDown().filter { it.isFile }.take(100_000).forEach { file ->
                out += GitChange(file.relativeTo(dir).path.replace('\\', '/'), GitChange.Kind.ADDED)
            }
        }
        st.changed.forEach { out += GitChange(it, GitChange.Kind.MODIFIED) }
        st.modified.forEach { out += GitChange(it, GitChange.Kind.MODIFIED) }
        st.removed.forEach { out += GitChange(it, GitChange.Kind.REMOVED) }
        st.missing.forEach { out += GitChange(it, GitChange.Kind.REMOVED) }
        out
    }

    fun remoteUrl(dir: File): String? = runCatching {
        Git.open(dir).use { it.repository.config.getString("remote", "origin", "url") }
    }.getOrNull()

    fun branch(dir: File): String? = runCatching { Git.open(dir).use { it.repository.branch } }.getOrNull()

    /** Nome e e-mail já configurados no repositório (se houver). */
    fun identity(dir: File): Pair<String, String> = runCatching {
        Git.open(dir).use {
            val config = it.repository.config
            config.getString("user", null, "name").orEmpty() to config.getString("user", null, "email").orEmpty()
        }
    }.getOrDefault("" to "")

    /** Arquivos novos ou alterados grandes demais para o GitHub (caminho relativo). */
    fun tooLarge(dir: File, changes: List<GitChange>): List<String> = changes
        .filter { it.kind != GitChange.Kind.REMOVED }
        .map { it.path }
        .distinct()
        .filter { GitLimits.isTooLarge(File(dir, it).length()) }

    /** Garante o .gitignore padrão e acrescenta os arquivos grandes demais (sem apagar nada que já exista nele). */
    fun updateGitignore(dir: File, extraIgnored: List<String>) {
        val file = File(dir, ".gitignore")
        val existing = if (file.exists()) file.readLines() else emptyList()
        val missingDefaults = GitLimits.DEFAULT_GITIGNORE.filter { it !in existing && !it.startsWith("#") }
        val missingLarge = extraIgnored.filter { "/$it" !in existing && it !in existing }.map { "/$it" }
        if (missingDefaults.isEmpty() && missingLarge.isEmpty() && file.exists()) return
        val text = buildString {
            existing.forEach { appendLine(it) }
            if (!file.exists()) GitLimits.DEFAULT_GITIGNORE.filter { it.startsWith("#") }.forEach { appendLine(it) }
            missingDefaults.forEach { appendLine(it) }
            if (missingLarge.isNotEmpty()) {
                appendLine("# Arquivos acima de 100 MB (o GitHub recusa)")
                missingLarge.forEach { appendLine(it) }
            }
        }
        file.writeText(text)
    }

    fun commitAll(dir: File, message: String, name: String, email: String, monitor: ProgressMonitor? = null) {
        openRepo(dir).use { git ->
            val author = PersonIdent(name, email)
            if (monitor != null) {
                // Registra em lotes para poder mostrar quantos arquivos já foram.
                val paths = status(dir).filter { it.kind != GitChange.Kind.REMOVED }.map { it.path }.distinct()
                if (paths.isNotEmpty()) {
                    monitor.beginTask("Registrando arquivos", paths.size)
                    paths.chunked(50).forEach { chunk ->
                        val add = git.add()
                        chunk.forEach { add.addFilepattern(it) }
                        add.call()
                        monitor.update(chunk.size)
                    }
                    monitor.endTask()
                }
            }
            // Com andamento, os arquivos novos e alterados já entraram em lotes acima; sem ele, entra tudo de uma vez.
            if (monitor == null) git.add().addFilepattern(".").call()
            // Segunda passada: registra os arquivos que sumiram.
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(false).call()
        }
    }

    /** Endereço do repositório remoto; link SSH é trocado por https (o envio é feito com token). Só força o envio (substitui a branch do GitHub) quando [replace] vem confirmado pelo usuário. */
    fun push(dir: File, remoteUrl: String, user: String, token: String, monitor: ProgressMonitor, replace: Boolean = false) {
        val target = GitRemote.toHttps(remoteUrl)
        check(target.isNotBlank()) { "este repositório não tem um endereço de envio (origin)" }
        openRepo(dir).use { git ->
            val branch = git.repository.fullBranch.orEmpty()
            check(branch.startsWith("refs/heads/")) { "o repositório está sem branch ativa (HEAD solto)" }
            val results = git.push()
                .setRemote(target)
                // Com [replace] o "+" troca a branch do GitHub pelo histórico daqui (o histórico de lá se perde).
                .setRefSpecs(RefSpec((if (replace) "+" else "") + "$branch:$branch"))
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
                .setProgressMonitor(monitor)
                .call()
            results.flatMap { it.remoteUpdates }.forEach { update ->
                when (update.status) {
                    RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                        error("a branch do GitHub tem outro histórico. Nada foi sobrescrito.")
                    RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                        error(update.message ?: "o GitHub recusou o envio (confira se algum arquivo passa de 100 MB)")
                    else -> error("o envio falhou (${update.status})")
                }
            }
        }
    }

    /** Define o `origin` de um repositório que ainda não tem. */
    fun setRemote(dir: File, url: String) {
        Git.open(dir).use { git ->
            val config = git.repository.config
            config.setString("remote", "origin", "url", url)
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.save()
        }
    }

    /**
     * Deixa arquivos de fora do commit só neste aparelho (`.git/info/exclude`), sem tocar no
     * `.gitignore` do repositório, que pode ser compartilhado com outros computadores.
     */
    fun addLocalExclude(dir: File, paths: List<String>) {
        if (paths.isEmpty()) return
        val file = File(dir, ".git/info/exclude").also { it.parentFile?.mkdirs() }
        val existing = if (file.exists()) file.readLines() else emptyList()
        val missing = paths.map { "/$it" }.filter { it !in existing }
        if (missing.isEmpty()) return
        file.appendText((if (existing.isNotEmpty() && file.readText().endsWith("\n").not()) "\n" else "") + missing.joinToString("\n", postfix = "\n"))
    }

    /** Confere URL e credenciais. Devolve true se o repositório remoto já tem alguma branch (não está vazio). */
    fun remoteHasBranches(url: String, user: String, token: String): Boolean =
        Git.lsRemoteRepository()
            .setRemote(url)
            .setHeads(true)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
            .call()
            .isNotEmpty()

    /** Branches do repositório remoto com o commit de cada uma. Não baixa nada. */
    fun remoteHeads(url: String, user: String, token: String): Map<String, String> =
        Git.lsRemoteRepository()
            .setRemote(url)
            .setHeads(true)
            .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
            .call()
            .filter { it.name.startsWith("refs/heads/") }
            .associate { it.name.removePrefix("refs/heads/") to it.objectId.name }

    /** O commit [remoteId] do GitHub está no histórico local? Então o envio é um avanço normal. */
    fun isAncestorOfHead(dir: File, remoteId: String): Boolean {
        val result = runCatching {
            openRepo(dir).use { git ->
                val repo = git.repository
                val head = repo.resolve("HEAD")
                if (head == null) {
                    false
                } else {
                    RevWalk(repo).use { walk ->
                        walk.isMergedInto(walk.parseCommit(ObjectId.fromString(remoteId)), walk.parseCommit(head))
                    }
                }
            }
        }
        // Commit que não existe aqui (alguém enviou de outro lugar) conta como "não é ancestral".
        return result.getOrDefault(false)
    }

    /**
     * A branch atual já existe no GitHub com um histórico que não é o da pasta? Nesse caso o envio só
     * é possível substituindo a branch do GitHub, o que o app só faz depois de perguntar.
     */
    fun sendNeedsReplace(dir: File, url: String, user: String, token: String): Boolean {
        val heads = remoteHeads(GitRemote.toHttps(url), user, token)
        val current = branch(dir) ?: return false
        val remoteId = heads[current] ?: return false
        return !isAncestorOfHead(dir, remoteId)
    }

    /** True se não há nada para registrar (pasta igual ao último commit). */
    fun isClean(dir: File): Boolean = Git.open(dir).use { it.status().call().isClean }

    fun init(dir: File, branch: String, url: String, name: String, email: String) {
        Git.init().setDirectory(dir).setInitialBranch(branch).call().use { git ->
            val config = git.repository.config
            config.setString("user", null, "name", name)
            config.setString("user", null, "email", email)
            config.setBoolean("core", null, "filemode", false)
            config.setString("remote", "origin", "url", url)
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.setString("branch", branch, "remote", "origin")
            config.setString("branch", branch, "merge", "refs/heads/$branch")
            config.save()
        }
    }
}
