package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupRulesTest {
    @Test fun appAndGitNamesAreProtected() {
        listOf("songs", "Playlists", ".trueplayer", "_lixeira", ".git", ".gitignore", ".gitattributes", "README.md", "LICENSE")
            .forEach { assertTrue(it, CleanupRules.isProtectedName(it)) }
    }

    @Test fun timestampFoldersAreCandidates() {
        assertFalse(CleanupRules.isProtectedName("2026-09-12 02-00-02"))
        assertFalse(CleanupRules.isProtectedName("capas"))
    }

    @Test fun musicAndPlaylistFilesAreLeftToTheOrganizer() {
        assertTrue(CleanupRules.isMusicFile("a.MP3"))
        assertTrue(CleanupRules.isMusicFile("lista.m3u8"))
        assertFalse(CleanupRules.isMusicFile("capa.jpg"))
        assertFalse(CleanupRules.isMusicFile("backup.json"))
    }

    @Test fun freeNameAddsCounterBeforeTheExtension() {
        assertEquals("a.txt", CleanupRules.freeName("a.txt", emptySet()))
        assertEquals("a (2).txt", CleanupRules.freeName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", CleanupRules.freeName("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("2026-09-12 (2)", CleanupRules.freeName("2026-09-12", setOf("2026-09-12")))
    }
}
