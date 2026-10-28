package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationPlannerTest {
    private fun plan(vararg paths: String) = MigrationPlanner.plan(paths.map { FolderFile(it) })

    @Test fun songsAndPlaylistsGoToTheirFolders() {
        val moves = plan("a.mp3", "Treino.m3u")
        assertEquals(
            listOf(
                PlannedMove("Treino.m3u", "playlists/Treino.m3u", PlannedMove.Kind.PLAYLIST),
                PlannedMove("a.mp3", "songs/a.mp3", PlannedMove.Kind.SONG),
            ).toSet(),
            moves.toSet(),
        )
    }

    @Test fun subfoldersAreKeptInsideSongs() {
        val move = plan("Artista/Album/x.flac").single()
        assertEquals("songs/Artista/Album/x.flac", move.to)
    }

    @Test fun playlistsInSubfoldersAreFlattened() {
        assertEquals("playlists/p.m3u8", plan("velhas/p.m3u8").single().to)
    }

    @Test fun alreadyOrganizedAndReservedFoldersAreUntouched() {
        val moves = plan(
            "songs/a.mp3", "playlists/p.m3u", ".trueplayer/stats.json", "_lixeira/b.mp3",
            ".git/objects/x", "foto.jpg", "2026-09-12 02-00-02/backup.json",
        )
        assertTrue(moves.isEmpty())
    }

    @Test fun collisionsAreRenamedNeverOverwritten() {
        val moves = plan("songs/a.mp3", "a.mp3", "X/a.mp3")
        val targets = moves.map { it.to }
        assertEquals(2, targets.size)
        assertEquals(targets.size, targets.toSet().size)
        assertTrue("songs/a.mp3" !in targets)
    }

    @Test fun sameNameInDifferentFoldersDoesNotCollideInSongs() {
        val targets = plan("A/x.mp3", "B/x.mp3").map { it.to }
        assertEquals(setOf("songs/A/x.mp3", "songs/B/x.mp3"), targets.toSet())
    }

    @Test fun uppercaseExtensionIsRecognized() {
        assertEquals("songs/a.MP3", plan("a.MP3").single().to)
    }
}
