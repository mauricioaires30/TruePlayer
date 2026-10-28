package dev.truetagger.android

import dev.truetagger.data.GitChange
import dev.truetagger.data.GitLimits
import dev.truetagger.data.GitRemote
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import dev.truetagger.data.GitRetry
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
        private val cancelled: () -> Boolean = { false },
        private val onText: (String) -> Unit,
    ) : ProgressMonitor {
        private var title = ""
        private var total = 0
        private var done = 0
        private var lastPercent = -1

        /** Texto antes da mensagem, para envios em várias partes ("Parte 2 de 3 • "). */
        @Volatile var prefix = ""

        /** Parte atual e total de partes de um envio dividido; a fração da etapa leva isso em conta. */
        @Volatile var partIndex = 0
        @Volatile var partCount = 1

        // Cada fase do envio ocupa uma faixa da etapa: contar 10%, compactar 20%, enviar 70%.
        private var from = 0f
        private var span = 1f

        private fun emit(phase: Float) {
            val inPart = (from + span * phase).coerceIn(0f, 1f)
            onFraction(((partIndex + inPart) / partCount.coerceAtLeast(1)).coerceIn(0f, 1f))
        }

        override fun start(totalTasks: Int) {}

        override fun beginTask(title: String, totalWork: Int) {
            this.title = translate(title)
            total = totalWork
            done = 0
            lastPercent = -1
            when (this.title) {
                "Contando arquivos" -> { from = 0f; span = 0.1f }
                "Compactando" -> { from = 0.1f; span = 0.2f }
                "Enviando" -> { from = 0.3f; span = 0.7f }
                else -> { from = 0f; span = 1f }
            }
            if (totalWork > 0) emit(0f) else onFraction(null)
            onText(prefix + if (totalWork > 0) "${this.title}: 0 de $totalWork (0%)" else this.title)
        }

        override fun update(completed: Int) {
            done += completed
            if (total <= 0) return
            val percent = (done * 100L / total).coerceAtMost(100).toInt()
            // Só avisa quando a porcentagem muda, para não atualizar a tela a cada arquivo.
            if (percent == lastPercent) return
            lastPercent = percent
            emit(percent / 100f)
            onText(prefix + "$title: ${done.coerceAtMost(total)} de $total ($percent%)")
        }

        override fun endTask() {
            // Depois de enviar tudo o GitHub ainda confere e monta o que recebeu; isso pode levar minutos.
            if (title == "Enviando") {
                // Quase no fim da etapa, mas ainda falta o GitHub confirmar: não mostra 100%.
                onFraction(((partIndex + 0.95f) / partCount.coerceAtLeast(1)).coerceIn(0f, 0.99f))
                onText(prefix + "Aguardando o GitHub confirmar…")
            }
        }

        /** Mostra uma mensagem avulsa (por exemplo "tentando de novo"), sem mexer na porcentagem. */
        fun say(text: String) = onText(prefix + text)

        override fun isCancelled(): Boolean = cancelled()
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

    /**
     * Tamanho de cada parte de um envio. O GitHub recusa envios acima de ~2 GB e responde erro 500 quando um
     * envio grande demora. Partes pequenas ficam salvas no GitHub uma a uma: se uma falha, o próximo envio
     * continua dali, sem repetir o que já foi.
     */
    const val MAX_PART_BYTES = 200L * 1024L * 1024L

    /** Sem resposta do GitHub por tanto tempo, o envio desiste (em segundos). */
    private const val NETWORK_TIMEOUT_S = 900

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
            // Músicas comuns (até 24 MB) seguem o caminho normal do JGit; só arquivos maiores são lidos em fluxo.
            cache.streamFileThreshold = 24 shl 20
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

    /**
     * Uma operação interrompida (app fechado, falta de memória) deixa `.git/index.lock` para trás, e depois
     * disso nada consegue registrar arquivos ("Cannot lock .git/index"). Trava com mais de 2 minutos é de uma
     * operação que já morreu e é apagada; trava recente pode ser de uma operação em andamento e vira erro.
     * Devolve quantas travas antigas foram removidas.
     */
    fun clearStaleLocks(dir: File, now: Long = System.currentTimeMillis()): Int {
        val git = File(dir, ".git")
        val heads = File(git, "refs/heads").listFiles { f -> f.name.endsWith(".lock") }?.toList().orEmpty()
        var removed = 0
        (listOf(File(git, "index.lock"), File(git, "HEAD.lock")) + heads).filter { it.isFile }.forEach { lock ->
            check(now - lock.lastModified() >= STALE_LOCK_MS) {
                "outra operação do Git está em andamento (${lock.name} é recente). Espere um minuto e tente de novo."
            }
            if (lock.delete()) removed++
        }
        return removed
    }

    private const val STALE_LOCK_MS = 2 * 60_000L

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

    /**
     * Registra tudo que mudou. Com [monitor] mostra o andamento e, se os arquivos novos passam de
     * [maxPartBytes], divide em vários commits ("... (parte 2 de 3)") para que cada envio caiba no limite do GitHub.
     */
    fun commitAll(dir: File, message: String, name: String, email: String, monitor: ProgressMonitor? = null, maxPartBytes: Long = MAX_PART_BYTES): List<String> {
        val skipped = ArrayList<String>()
        openRepo(dir).use { git ->
            val author = PersonIdent(name, email)
            if (monitor == null) {
                git.add().addFilepattern(".").call()
                git.add().setUpdate(true).addFilepattern(".").call()
                git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(false).call()
                return skipped
            }
            val paths = status(dir).filter { it.kind != GitChange.Kind.REMOVED }.map { it.path }.distinct()
            val parts = splitBySize(paths.map { it to File(dir, it).length() }, maxPartBytes)
            // Os arquivos que sumiram entram no primeiro commit.
            git.add().setUpdate(true).addFilepattern(".").call()
            if (parts.isEmpty()) {
                git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(false).call()
                return skipped
            }
            monitor.beginTask("Registrando arquivos", paths.size)
            parts.forEachIndexed { index, part ->
                part.chunked(50).forEach { chunk ->
                    try {
                        val add = git.add()
                        chunk.forEach { add.addFilepattern(it) }
                        add.call()
                    } catch (error: Exception) {
                        // Um arquivo ilegível (sem permissão, sumiu no meio) não pode derrubar o commit todo:
                        // tenta um por um e deixa de fora só o que falhar.
                        chunk.forEach { path ->
                            try {
                                git.add().addFilepattern(path).call()
                            } catch (single: Exception) {
                                skipped += path
                            }
                        }
                    }
                    monitor.update(chunk.size)
                }
                val text = if (parts.size > 1) "$message (parte ${index + 1} de ${parts.size})" else message
                // Se tudo o que havia nesta parte falhou e não sobrou nada para registrar, não é erro.
                try {
                    git.commit().setMessage(text).setAuthor(author).setCommitter(author).setAllowEmpty(false).call()
                } catch (empty: org.eclipse.jgit.api.errors.EmptyCommitException) {
                    if (skipped.isEmpty()) throw empty
                }
            }
            monitor.endTask()
        }
        return skipped
    }

    /** Divide em grupos de até [limit] bytes, na ordem. Um arquivo maior que o limite fica sozinho no grupo. */
    fun splitBySize(files: List<Pair<String, Long>>, limit: Long): List<List<String>> {
        val parts = ArrayList<MutableList<String>>()
        var size = 0L
        for ((path, bytes) in files) {
            if (parts.isEmpty() || (size + bytes > limit && parts.last().isNotEmpty())) {
                parts.add(mutableListOf())
                size = 0L
            }
            parts.last().add(path)
            size += bytes
        }
        return parts
    }

    /** Commits locais que o GitHub ainda não tem, do mais antigo ao mais novo. */
    private fun unpushedCommits(repo: Repository, head: ObjectId, remoteTip: String?): List<RevCommit> =
        RevWalk(repo).use { walk ->
            walk.sort(RevSort.TOPO)
            walk.sort(RevSort.REVERSE, true)
            walk.markStart(walk.parseCommit(head))
            // Se o commit do GitHub não existe aqui, ele não tem relação com o nosso histórico: manda tudo.
            remoteTip?.let { runCatching { walk.markUninteresting(walk.parseCommit(ObjectId.fromString(it))) } }
            walk.toList()
        }

    /** Bytes que o commit acrescenta em relação ao pai (arquivos novos ou alterados). */
    private fun commitBytes(repo: Repository, commit: RevCommit): Long {
        val parentTree = commit.parents.firstOrNull()?.let { parent -> RevWalk(repo).use { it.parseCommit(parent).tree } }
        return repo.newObjectReader().use { reader ->
            TreeWalk(repo).use { tw ->
                if (parentTree != null) tw.addTree(parentTree) else tw.addTree(EmptyTreeIterator())
                tw.addTree(commit.tree)
                tw.isRecursive = true
                var sum = 0L
                while (tw.next()) {
                    val before = tw.getObjectId(0)
                    val after = tw.getObjectId(1)
                    if (after != ObjectId.zeroId() && before != after) sum += reader.getObjectSize(after, Constants.OBJ_BLOB)
                }
                sum
            }
        }
    }

    /**
     * Se algum commit ainda não enviado é maior que [maxPartBytes] (por exemplo, um feito por uma versão antiga
     * com partes grandes), refaz esses commits em partes menores. Só mexe no histórico que o GitHub ainda não
     * tem, e só quando a pasta está igual ao último commit: assim nada do que está na pasta se perde.
     * Devolve true se refez.
     */
    fun resplitUnpushed(dir: File, remoteTip: String?, monitor: ProgressMonitor, maxPartBytes: Long = MAX_PART_BYTES): Boolean {
        val plan = openRepo(dir).use { git ->
            val repo = git.repository
            val head = repo.resolve("HEAD") ?: return false
            val commits = unpushedCommits(repo, head, remoteTip)
            if (commits.isEmpty() || commits.none { commitBytes(repo, it) > maxPartBytes }) return false
            if (!git.status().call().isClean) return false
            // Ponto de partida: o último commit que o GitHub tem (se ele faz parte do nosso histórico) ou o começo.
            val base = remoteTip?.let { runCatching { ObjectId.fromString(it) }.getOrNull() }?.takeIf { id ->
                repo.hasObject(id) && RevWalk(repo).use { walk ->
                    runCatching { walk.isMergedInto(walk.parseCommit(id), walk.parseCommit(head)) }.getOrDefault(false)
                }
            }
            val last = commits.last()
            val message = last.fullMessage.trim().replace(Regex(" \\(parte \\d+ de \\d+\\)$"), "")
            Triple(base, message, last.authorIdent)
        }
        val (base, message, author) = plan

        openRepo(dir).use { git ->
            val repo = git.repository
            val branch = repo.branch ?: return false
            if (base != null) {
                git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(base.name).call()
            } else {
                // Volta ao estado de repositório novo (o JGit recusa apagar a branch atual, então o arquivo da referência é solto).
                val packed = File(dir, ".git/packed-refs")
                if (packed.isFile && packed.readText().contains("refs/heads/$branch")) return false
                val ref = File(dir, ".git/refs/heads/$branch")
                if (!ref.isFile || !ref.delete()) return false
                File(dir, ".git/index").delete()
                repo.refDatabase.refresh()
            }
        }
        commitAll(dir, message, author.name, author.emailAddress, monitor, maxPartBytes)
        return true
    }

    /**
     * Envia a branch atual. Endereço SSH é trocado por https (o envio é feito com token). Commits grandes são
     * refeitos em partes menores e as partes vão uma de cada vez (o GitHub recusa envios acima de ~2 GB e falha
     * com erro 500 em envios longos). Falhas passageiras do GitHub ou da conexão são repetidas sozinhas.
     * Só força o envio (substitui a branch do GitHub) quando [replace] vem confirmado pelo usuário.
     */
    fun push(
        dir: File,
        remoteUrl: String,
        user: String,
        token: String,
        monitor: ProgressMonitor,
        replace: Boolean = false,
        maxPartBytes: Long = MAX_PART_BYTES,
        retryDelaysMs: List<Long> = listOf(5_000L, 20_000L, 60_000L),
    ) {
        val target = GitRemote.toHttps(remoteUrl)
        check(target.isNotBlank()) { "este repositório não tem um endereço de envio (origin)" }
        val branch = Git.open(dir).use { it.repository.fullBranch.orEmpty() }
        check(branch.startsWith("refs/heads/")) { "o repositório está sem branch ativa (HEAD solto)" }
        val remoteTip = remoteHeads(target, user, token)[branch.removePrefix("refs/heads/")]
        val textMonitor = monitor as? TextMonitor

        textMonitor?.prefix = "Dividindo em partes menores • "
        resplitUnpushed(dir, remoteTip, monitor, maxPartBytes)
        textMonitor?.prefix = ""

        openRepo(dir).use { git ->
            val repo = git.repository
            val head = repo.resolve("HEAD") ?: error("não há commits para enviar")
            val commits = unpushedCommits(repo, head, remoteTip).map { it.name }.ifEmpty { listOf(head.name) }
            commits.forEachIndexed { index, id ->
                textMonitor?.let {
                    it.prefix = if (commits.size > 1) "Parte ${index + 1} de ${commits.size} • " else ""
                    it.partIndex = index
                    it.partCount = commits.size
                }
                // Com [replace] o "+" troca a branch do GitHub pelo histórico daqui (o histórico de lá se perde).
                val force = if (replace && index == 0) "+" else ""
                val results = GitRetry.run(
                    delaysMs = retryDelaysMs,
                    shouldRetry = { GitRetry.isTransient(it) && !monitor.isCancelled },
                    onRetry = { attempt, delay, error ->
                        textMonitor?.say("O GitHub falhou (${GitRetry.brief(error)}). Tentando de novo em ${delay / 1000} s ($attempt de ${retryDelaysMs.size})…")
                    },
                    sleep = { ms ->
                        var left = ms
                        while (left > 0 && !monitor.isCancelled) {
                            Thread.sleep(minOf(500L, left))
                            left -= 500L
                        }
                    },
                ) {
                    git.push()
                        .setRemote(target)
                        .setRefSpecs(RefSpec("$force$id:$branch"))
                        .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
                        .setProgressMonitor(monitor)
                        .setTimeout(NETWORK_TIMEOUT_S)
                        .call()
                }
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
            textMonitor?.let { it.prefix = ""; it.partIndex = 0; it.partCount = 1 }
        }
    }

    /** Resultado de [pull]: arquivos baixados para a pasta. */
    data class Pulled(val files: List<String>, val upToDate: Boolean)

    /**
     * Baixa do GitHub o que só existe lá. Nunca sobrescreve nada que você mudou: arquivo que só existe no
     * GitHub é baixado; arquivo que o GitHub mudou e você não tocou é atualizado; arquivo que você mudou
     * continua como está (e vai no próximo envio). A branch local passa a seguir a do GitHub, para o
     * próximo envio ser um avanço normal.
     */
    fun pull(dir: File, remoteUrl: String, user: String, token: String, monitor: ProgressMonitor): Pulled {
        val target = GitRemote.toHttps(remoteUrl)
        check(target.isNotBlank()) { "este repositório não tem um endereço (origin)" }
        openRepo(dir).use { git ->
            val repo = git.repository
            git.fetch()
                .setRemote(target)
                .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(user, token))
                .setProgressMonitor(monitor)
                .setTimeout(NETWORK_TIMEOUT_S)
                .call()
            val wanted = repo.branch.orEmpty()
            val prefix = "refs/remotes/origin/"
            val remotes = repo.refDatabase.getRefsByPrefix(prefix).filter { !it.name.endsWith("/HEAD") }
            val chosen = listOf(wanted, "main", "master")
                .firstNotNullOfOrNull { name -> remotes.firstOrNull { it.name == prefix + name } }
                ?: remotes.firstOrNull()
                ?: error("o repositório do GitHub está vazio")
            val name = chosen.name.removePrefix(prefix)
            val remoteId = chosen.objectId

            val oldHead = repo.resolve("HEAD")
            RevWalk(repo).use { walk ->
                if (oldHead != null && walk.isMergedInto(walk.parseCommit(remoteId), walk.parseCommit(oldHead))) {
                    return Pulled(emptyList(), upToDate = true)
                }
            }
            // O que o usuário mexeu desde o último commit não pode ser sobrescrito.
            val before = git.status().call()
            val touched = (before.modified + before.changed + before.added + before.removed).toSet()

            val move = repo.updateRef("refs/heads/$name")
            move.setNewObjectId(remoteId)
            move.setForceUpdate(true)
            move.update()
            repo.updateRef(org.eclipse.jgit.lib.Constants.HEAD).link("refs/heads/$name")
            if (name != wanted && wanted.isNotBlank()) {
                val config = repo.config
                config.unsetSection("branch", wanted)
                config.setString("branch", name, "remote", "origin")
                config.setString("branch", name, "merge", "refs/heads/$name")
                config.save()
            }
            // Índice igual ao do GitHub e pasta intocada: o que difere aparece como mudança.
            git.reset().setMode(ResetCommand.ResetType.MIXED).setRef("HEAD").call()

            val toRestore = LinkedHashSet<String>()
            toRestore += git.status().call().missing
            if (oldHead != null) toRestore += changedUpstream(repo, oldHead, remoteId).filter { it !in touched && File(dir, it).isFile }
            if (toRestore.isNotEmpty()) git.checkout().addPaths(toRestore.toList()).call()
            return Pulled(toRestore.toList(), upToDate = false)
        }
    }

    /** Arquivos que mudaram entre dois commits (caminhos). */
    private fun changedUpstream(repo: Repository, from: ObjectId, to: ObjectId): List<String> =
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tw ->
                tw.addTree(walk.parseCommit(from).tree)
                tw.addTree(walk.parseCommit(to).tree)
                tw.isRecursive = true
                val out = ArrayList<String>()
                while (tw.next()) {
                    val a = tw.getObjectId(0)
                    val b = tw.getObjectId(1)
                    // Só o que já existia no commit antigo e mudou: arquivo novo que você já tem na pasta não é tocado.
                    if (a != ObjectId.zeroId() && b != ObjectId.zeroId() && a != b) out += tw.pathString
                }
                out
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
            .setTimeout(60)
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
