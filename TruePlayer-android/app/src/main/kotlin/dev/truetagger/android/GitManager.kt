package dev.truetagger.android

import dev.truetagger.data.GitChange
import dev.truetagger.data.GitLimits
import dev.truetagger.data.GitRemote
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Operações de git (JGit) na pasta de músicas. Todas bloqueiam: chame fora da thread principal. */
internal object GitManager {
    /** Mostra em português o andamento que o JGit informa em inglês. */
    class TextMonitor(private val onText: (String) -> Unit) : ProgressMonitor {
        private var title = ""
        private var total = 0
        private var done = 0

        override fun start(totalTasks: Int) {}

        override fun beginTask(title: String, totalWork: Int) {
            this.title = translate(title)
            total = totalWork
            done = 0
            onText(this.title)
        }

        override fun update(completed: Int) {
            done += completed
            if (total > 0) onText("$title ${(done * 100L / total).coerceAtMost(100)}%")
        }

        override fun endTask() {}
        override fun isCancelled(): Boolean = false
        override fun showDuration(enabled: Boolean) {}

        private fun translate(text: String) = when {
            text.contains("Counting", true) -> "Contando arquivos"
            text.contains("Compressing", true) -> "Compactando"
            text.contains("Writing", true) -> "Enviando"
            text.contains("Finding sources", true) -> "Preparando"
            else -> text
        }
    }

    fun hasRepo(dir: File) = File(dir, ".git").exists()

    fun status(dir: File): List<GitChange> = Git.open(dir).use { git ->
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
        Git.open(dir).use { git ->
            val author = PersonIdent(name, email)
            git.add().addFilepattern(".").call()
            // Segunda passada: registra os arquivos que sumiram.
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(false).call()
        }
    }

    /** Endereço do repositório remoto; link SSH é trocado por https (o envio é feito com token). Nunca força o envio. */
    fun push(dir: File, remoteUrl: String, user: String, token: String, monitor: ProgressMonitor) {
        val target = GitRemote.toHttps(remoteUrl)
        check(target.isNotBlank()) { "este repositório não tem um endereço de envio (origin)" }
        Git.open(dir).use { git ->
            val branch = git.repository.fullBranch.orEmpty()
            check(branch.startsWith("refs/heads/")) { "o repositório está sem branch ativa (HEAD solto)" }
            val results = git.push()
                .setRemote(target)
                .add(branch)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
                .setProgressMonitor(monitor)
                .call()
            results.flatMap { it.remoteUpdates }.forEach { update ->
                when (update.status) {
                    RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                        error("o repositório no GitHub tem mudanças que não estão aqui. Nada foi sobrescrito.")
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
