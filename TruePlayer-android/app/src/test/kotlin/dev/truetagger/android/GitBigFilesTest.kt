package dev.truetagger.android

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Músicas de verdade passam de 1 MB: commit, envio e download têm que funcionar com arquivos assim (e não comprimíveis). */
class GitBigFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun bareRemote(): File = temp.newFolder("remoto.git").also { Git.init().setBare(true).setDirectory(it).setInitialBranch("main").call().close() }

    private fun songs(dir: File, count: Int, megabytes: Int) {
        File(dir, "songs").mkdirs()
        val random = Random(42)
        repeat(count) { i ->
            val bytes = ByteArray(megabytes * 1024 * 1024).also { random.nextBytes(it) }
            File(dir, "songs/musica-$i.mp3").writeBytes(bytes)
        }
    }

    @Test fun bigAudioFilesCanBeCommittedPushedAndPulled() {
        val remote = bareRemote()
        val url = remote.toURI().toString()
        val dir = temp.newFolder("celular")
        // mais arquivos grandes do que o cache de Inflater do JGit comporta (4)
        songs(dir, count = 14, megabytes = 2)
        GitManager.init(dir, "main", url, "Fulano", "f@x.com")
        GitManager.updateGitignore(dir, emptyList())

        GitManager.commitAll(dir, "Músicas grandes", "Fulano", "f@x.com", GitManager.TextMonitor { })
        assertTrue(GitManager.isClean(dir))
        GitManager.push(dir, url, "x", "", GitManager.TextMonitor { })

        // outro celular baixa tudo
        val other = temp.newFolder("outro-celular")
        GitManager.init(other, "main", url, "Fulano", "f@x.com")
        val pulled = GitManager.pull(other, url, "x", "", GitManager.TextMonitor { })
        assertEquals(14, pulled.files.count { it.endsWith(".mp3") })
        (0 until 14).forEach { i ->
            assertEquals(File(dir, "songs/musica-$i.mp3").length(), File(other, "songs/musica-$i.mp3").length())
            assertTrue(File(dir, "songs/musica-$i.mp3").readBytes().contentEquals(File(other, "songs/musica-$i.mp3").readBytes()))
        }
    }
}
