package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GitSummaryTest {
    private val date = LocalDate.of(2026, 10, 27)

    private fun added(vararg p: String) = p.map { GitChange(it, GitChange.Kind.ADDED) }
    private fun modified(vararg p: String) = p.map { GitChange(it, GitChange.Kind.MODIFIED) }
    private fun removed(vararg p: String) = p.map { GitChange(it, GitChange.Kind.REMOVED) }

    @Test fun groupsChangesByMeaning() {
        val summary = GitSummary.from(
            added("songs/a.mp3", "songs/Sub/b.flac", "playlists/Treino.m3u") +
                modified("songs/c.mp3", "playlists/Estudo.m3u", ".trueplayer/stats.json") +
                removed("songs/d.mp3", "playlists/Velha.m3u") +
                added("_lixeira/x/foto.jpg", ".trueplayer/settings.json"),
        )
        assertEquals(listOf("a.mp3", "b.flac"), summary.songsAdded)
        assertEquals(listOf("d.mp3"), summary.songsRemoved)
        assertEquals(listOf("c.mp3"), summary.tagsEdited)
        assertEquals(listOf("Treino"), summary.playlistsAdded)
        assertEquals(listOf("Estudo"), summary.playlistsChanged)
        assertEquals(listOf("Velha"), summary.playlistsRemoved)
        assertTrue(summary.statsChanged)
        assertEquals(2, summary.other)
        assertFalse(summary.isEmpty)
    }

    @Test fun noChangesIsEmpty() {
        assertTrue(GitSummary.from(emptyList()).isEmpty)
    }

    @Test fun windowsSeparatorsAreHandled() {
        val summary = GitSummary.from(added("songs\\a.mp3"))
        assertEquals(listOf("a.mp3"), summary.songsAdded)
    }

    @Test fun messageIncludesDateCountsAndMinutes() {
        val summary = GitSummary.from(
            added("songs/a.mp3", "songs/b.mp3", "songs/c.mp3", "playlists/Treino.m3u") +
                modified("songs/1.mp3", "songs/2.mp3", "songs/3.mp3", "songs/4.mp3", "songs/5.mp3"),
        )
        assertEquals(
            "2026-10-27: +3 músicas, +1 playlist (Treino), 5 tags editadas, 142 min ouvidos",
            CommitMessage.build(summary, date, 142),
        )
    }

    @Test fun singularAndPluralForms() {
        val one = CommitMessage.build(GitSummary.from(added("songs/a.mp3") + modified("songs/b.mp3")), date)
        assertEquals("2026-10-27: +1 música, 1 tag editada", one)
    }

    @Test fun manyPlaylistNamesAreTruncated() {
        val summary = GitSummary.from(added("playlists/A.m3u", "playlists/B.m3u", "playlists/C.m3u", "playlists/D.m3u", "playlists/E.m3u"))
        assertEquals("2026-10-27: +5 playlists (A, B, C e mais 2)", CommitMessage.build(summary, date))
    }

    @Test fun messageWithoutRecognizedChangesStillMakesSense() {
        assertEquals("2026-10-27: atualização da pasta", CommitMessage.build(GitSummary.from(added("_lixeira/x")), date))
    }

    @Test fun largeFileLimit() {
        assertFalse(GitLimits.isTooLarge(50L * 1024 * 1024))
        assertTrue(GitLimits.isTooLarge(96L * 1024 * 1024))
    }
}
