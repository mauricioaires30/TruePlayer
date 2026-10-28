package dev.truetagger.android

import dev.truetagger.data.CommitMessage
import dev.truetagger.data.GitChange
import dev.truetagger.data.GitSummary
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Roda o JGit de verdade (no PC) contra pastas temporárias e um repositório "remoto" local. */
class GitManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun music(): File = temp.newFolder("musicas").also {
        File(it, "songs").mkdirs()
        File(it, "playlists").mkdirs()
        File(it, ".trueplayer").mkdirs()
        File(it, "songs/a.mp3").writeText("audio a")
        File(it, "songs/b.mp3").writeText("audio b")
        File(it, "playlists/Treino.m3u").writeText("#EXTM3U\n../songs/a.mp3\n")
    }

    private fun bareRemote(name: String = "remoto.git"): File = temp.newFolder(name).also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }

    private fun summaryOf(dir: File) = GitSummary.from(GitManager.status(dir))

    @Test fun initCommitAndTrackChanges() {
        val dir = music()
        GitManager.init(dir, "main", "https://github.com/x/y.git", "Fulano", "f@x.com")
        assertTrue(GitManager.hasRepo(dir))
        assertEquals("main", GitManager.branch(dir))
        assertEquals("https://github.com/x/y.git", GitManager.remoteUrl(dir))
        assertEquals("Fulano" to "f@x.com", GitManager.identity(dir))

        // tudo é novo antes do primeiro commit
        assertEquals(listOf("a.mp3", "b.mp3"), summaryOf(dir).songsAdded.sorted())

        GitManager.updateGitignore(dir, emptyList())
        GitManager.commitAll(dir, "Primeiro commit", "Fulano", "f@x.com")
        assertTrue(summaryOf(dir).let { it.songsAdded.isEmpty() && it.playlistsAdded.isEmpty() })

        // música nova, tag editada (arquivo mudou), música apagada, playlist alterada, stats
        File(dir, "songs/c.mp3").writeText("audio c")
        File(dir, "songs/a.mp3").writeText("audio a com tag nova")
        File(dir, "songs/b.mp3").delete()
        File(dir, "playlists/Treino.m3u").writeText("#EXTM3U\n../songs/a.mp3\n../songs/c.mp3\n")
        File(dir, ".trueplayer/stats.json").writeText("{}")
        val summary = summaryOf(dir)
        assertEquals(listOf("c.mp3"), summary.songsAdded)
        assertEquals(listOf("a.mp3"), summary.tagsEdited)
        assertEquals(listOf("b.mp3"), summary.songsRemoved)
        assertEquals(listOf("Treino"), summary.playlistsChanged)
        assertTrue(summary.statsChanged)

        val message = CommitMessage.build(summary, LocalDate.of(2026, 10, 27), 12)
        assertEquals("2026-10-27: +1 música, -1 música, 1 playlist alterada (Treino), 1 tag editada, 12 min ouvidos", message)
        GitManager.commitAll(dir, message, "Fulano", "f@x.com")
        assertTrue(summaryOf(dir).isEmpty)
        Git.open(dir).use { git -> assertEquals(message, git.log().call().first().fullMessage) }
    }

    @Test fun foldersCreatedByTheAppStayOutOfTheCommit() {
        val dir = music()
        File(dir, "_lixeira/2026-10-27 10-00-00").mkdirs()
        File(dir, "_lixeira/2026-10-27 10-00-00/velho.txt").writeText("x")
        File(dir, ".trueplayer/backup").mkdirs()
        File(dir, ".trueplayer/backup/old.json").writeText("{}")
        GitManager.init(dir, "main", "https://github.com/x/y.git", "F", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())
        GitManager.commitAll(dir, "c", "F", "f@x.com")
        Git.open(dir).use { git ->
            val tracked = git.repository.resolve("HEAD^{tree}").let { tree ->
                org.eclipse.jgit.treewalk.TreeWalk(git.repository).use { walk ->
                    walk.addTree(tree); walk.isRecursive = true
                    generateSequence { if (walk.next()) walk.pathString else null }.toList()
                }
            }
            assertTrue(tracked.contains("songs/a.mp3"))
            assertFalse(tracked.any { it.startsWith("_lixeira/") || it.startsWith(".trueplayer/backup/") })
        }
    }

    @Test fun gitignoreKeepsExistingLinesAndAddsLargeFilesOnce() {
        val dir = music()
        File(dir, ".gitignore").writeText("meu-arquivo.txt\n")
        GitManager.updateGitignore(dir, listOf("songs/enorme.wav"))
        GitManager.updateGitignore(dir, listOf("songs/enorme.wav"))
        val lines = File(dir, ".gitignore").readLines()
        assertTrue("meu-arquivo.txt" in lines)
        assertTrue("/songs/enorme.wav" in lines)
        assertEquals(1, lines.count { it == "/songs/enorme.wav" })
        assertTrue("_lixeira/" in lines)
    }

    @Test fun detectsFilesTooLargeForGithub() {
        val dir = music()
        val big = File(dir, "songs/enorme.wav")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(96L * 1024 * 1024) }
        val changes = listOf(GitChange("songs/enorme.wav", GitChange.Kind.ADDED), GitChange("songs/a.mp3", GitChange.Kind.ADDED))
        assertEquals(listOf("songs/enorme.wav"), GitManager.tooLarge(dir, changes))
    }

    @Test fun pushSendsTheCommitToAnEmptyRemote() {
        val remote = bareRemote()
        val dir = music()
        assertFalse(GitManager.remoteHasBranches(remote.absolutePath, "u", "t"))
        GitManager.init(dir, "main", remote.absolutePath, "F", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())
        GitManager.commitAll(dir, "Primeiro commit do TruePlayer", "F", "f@x.com")
        GitManager.push(dir, remote.absolutePath, "u", "t", GitManager.TextMonitor { })
        Git.open(remote).use { git -> assertNotNull(git.repository.resolve("refs/heads/main")) }
        assertTrue(GitManager.remoteHasBranches(remote.absolutePath, "u", "t"))
    }

    @Test fun pushNeverOverwritesDifferentHistory() {
        val remote = bareRemote()
        val first = music()
        GitManager.init(first, "main", remote.absolutePath, "F", "f@x.com")
        GitManager.commitAll(first, "um", "F", "f@x.com")
        GitManager.push(first, remote.absolutePath, "u", "t", GitManager.TextMonitor { })

        // Outra pasta com outra história tentando subir para o mesmo remoto.
        val second = temp.newFolder("outra").also { File(it, "x.txt").writeText("outro") }
        GitManager.init(second, "main", remote.absolutePath, "G", "g@x.com")
        GitManager.commitAll(second, "outro", "G", "g@x.com")
        try {
            GitManager.push(second, remote.absolutePath, "u", "t", GitManager.TextMonitor { })
            fail("o envio com histórico diferente deveria ter sido recusado")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Nada foi sobrescrito"))
        }
        // o conteúdo do primeiro continua no remoto
        Git.open(remote).use { git -> assertEquals("um", git.log().add(git.repository.resolve("refs/heads/main")).call().first().fullMessage) }
    }

    @Test fun existingRepoKeepsItsGitignoreAndLargeFilesAreExcludedLocally() {
        val dir = music()
        GitManager.init(dir, "main", "git@github.com:u/r.git", "F", "f@x.com")
        File(dir, ".gitignore").writeText("meu-ignore\n")
        GitManager.commitAll(dir, "base", "F", "f@x.com")
        File(dir, "songs/enorme.wav").writeText("x")
        GitManager.addLocalExclude(dir, listOf("songs/enorme.wav"))
        GitManager.addLocalExclude(dir, listOf("songs/enorme.wav"))

        assertEquals("meu-ignore\n", File(dir, ".gitignore").readText())
        assertEquals(1, File(dir, ".git/info/exclude").readLines().count { it == "/songs/enorme.wav" })
        assertTrue(summaryOf(dir).songsAdded.isEmpty())
    }

    @Test fun commitWithProgressRegistersEverythingAndReportsNumbers() {
        val dir = music()
        GitManager.init(dir, "main", "https://github.com/x/y.git", "Fulano", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())
        val first = ArrayList<String>()
        GitManager.commitAll(dir, "Primeiro", "Fulano", "f@x.com", GitManager.TextMonitor { first += it })
        assertTrue(GitManager.isClean(dir))
        assertTrue(first.first().startsWith("Registrando arquivos: 0 de "))
        assertTrue(first.last().endsWith("(100%)"))

        // pasta nova com subpastas, arquivo alterado e arquivo apagado
        File(dir, "songs/Artista/Album").mkdirs()
        File(dir, "songs/Artista/Album/nova.mp3").writeText("nova")
        File(dir, "songs/a.mp3").writeText("a editada")
        File(dir, "songs/b.mp3").delete()
        GitManager.commitAll(dir, "Segundo", "Fulano", "f@x.com", GitManager.TextMonitor { })
        assertTrue(GitManager.isClean(dir))
        Git.open(dir).use { git ->
            val head = git.repository.resolve("HEAD^{tree}")
            val names = org.eclipse.jgit.treewalk.TreeWalk(git.repository).use { walk ->
                walk.addTree(head); walk.isRecursive = true
                generateSequence { if (walk.next()) walk.pathString else null }.toList()
            }
            assertTrue("songs/Artista/Album/nova.mp3" in names)
            assertFalse("songs/b.mp3" in names)
        }
    }

    /** Cria um "GitHub" com um histórico próprio (a.mp3, b.mp3 e so-no-github.mp3) e devolve a pasta bare. */
    private fun remoteWithOtherHistory(): File {
        val remote = bareRemote()
        val seed = temp.newFolder("semente")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "songs").mkdirs()
            File(seed, "songs/a.mp3").writeText("audio a")
            File(seed, "songs/b.mp3").writeText("b antigo do github")
            File(seed, "songs/so-no-github.mp3").writeText("so la")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Antigo").setAuthor("Outro", "o@x.com").setCommitter("Outro", "o@x.com").call()
            git.push().setRemote(remote.toURI().toString()).add("refs/heads/main").call()
        }
        return remote
    }

    @Test fun remoteHeadsListsBranchesWithoutDownloadingAnything() {
        val remote = remoteWithOtherHistory()
        val heads = GitManager.remoteHeads(remote.toURI().toString(), "x", "")
        assertEquals(setOf("main"), heads.keys)
        assertEquals(40, heads.getValue("main").length)
        assertTrue(GitManager.remoteHeads(bareRemote("vazio.git").toURI().toString(), "x", "").isEmpty())
    }

    @Test fun sendNeedsReplaceOnlyWhenTheGithubBranchHasAnotherHistory() {
        val url = remoteWithOtherHistory().toURI().toString()
        val dir = music()
        GitManager.init(dir, "main", url, "Fulano", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())
        GitManager.commitAll(dir, "Nosso", "Fulano", "f@x.com")
        // histórico diferente do de lá
        assertTrue(GitManager.sendNeedsReplace(dir, url, "x", ""))

        // depois de enviar (substituindo), um novo commit é um avanço normal
        GitManager.push(dir, url, "x", "", GitManager.TextMonitor { }, replace = true)
        File(dir, "songs/c.mp3").writeText("c")
        GitManager.commitAll(dir, "Mais um", "Fulano", "f@x.com")
        assertFalse(GitManager.sendNeedsReplace(dir, url, "x", ""))

        // remoto vazio nunca pede para substituir
        val empty = bareRemote("vazio.git").toURI().toString()
        assertFalse(GitManager.sendNeedsReplace(dir, empty, "x", ""))
    }

    @Test fun replacingMakesTheGithubEqualToTheFolderAndPlainPushStillRefuses() {
        val remote = remoteWithOtherHistory()
        val url = remote.toURI().toString()
        val dir = music()
        GitManager.init(dir, "main", url, "Fulano", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())
        GitManager.commitAll(dir, "Nosso", "Fulano", "f@x.com")

        try {
            GitManager.push(dir, url, "x", "", GitManager.TextMonitor { })
            fail("o envio sem confirmar não pode sobrescrever")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("outro histórico"))
        }
        Git.open(remote).use { assertEquals(listOf("Antigo"), it.log().call().map { c -> c.fullMessage.trim() }) }

        GitManager.push(dir, url, "x", "", GitManager.TextMonitor { }, replace = true)
        Git.open(remote).use { assertEquals(listOf("Nosso"), it.log().call().map { c -> c.fullMessage.trim() }) }
    }

    @Test fun repoWithoutOriginCanReceiveOne() {
        val dir = music()
        Git.init().setDirectory(dir).setInitialBranch("main").call().close()
        assertEquals(null, GitManager.remoteUrl(dir))
        GitManager.setRemote(dir, "https://github.com/u/r.git")
        assertEquals("https://github.com/u/r.git", GitManager.remoteUrl(dir))
    }

    @Test fun pushWithoutAnAddressFailsClearly() {
        val dir = music()
        GitManager.init(dir, "main", "https://github.com/u/r.git", "F", "f@x.com")
        try {
            GitManager.push(dir, "", "u", "t", GitManager.TextMonitor { })
            fail("deveria falhar sem endereço")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("origin"))
        }
    }
}
