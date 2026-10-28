package dev.truetagger.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackKeysTest {
    @Test fun relPathFromRelativePathColumn() {
        assertEquals(
            "songs/Faixa.mp3",
            TrackKeys.relPath("Music/TruePlayer/", "Music/TruePlayer/songs/", "Faixa.mp3"),
        )
    }

    @Test fun relPathAtRoot() {
        assertEquals("Faixa.mp3", TrackKeys.relPath("Music/TruePlayer/", "Music/TruePlayer/", "Faixa.mp3"))
    }

    @Test fun relPathFromAbsoluteDataColumn() {
        assertEquals(
            "songs/A/B/x.mp3",
            TrackKeys.relPath("Music/TruePlayer/", "/storage/emulated/0/Music/TruePlayer/songs/A/B/x.mp3", "x.mp3"),
        )
    }

    @Test fun normalizeResolvesDotSegments() {
        assertEquals("songs/a.mp3", TrackKeys.normalize("playlists/../songs/./a.mp3"))
        assertEquals("songs/a.mp3", TrackKeys.normalize("/songs\\a.mp3"))
    }

    @Test fun compareKeyIgnoresCase() {
        assertEquals(TrackKeys.compareKey("Songs/A.MP3"), TrackKeys.compareKey("songs/a.mp3"))
    }
}
