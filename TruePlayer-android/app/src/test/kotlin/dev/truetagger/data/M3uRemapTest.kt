package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uRemapTest {
    private val map = mapOf("a.mp3" to "songs/a.mp3", "sub/b.flac" to "songs/sub/b.flac")

    @Test fun keysInTheMapAreReplacedAndOrderIsKept() {
        val entries = listOf(PlaylistEntry("sub/b.flac", "B"), PlaylistEntry("a.mp3", "A"))
        assertEquals(
            listOf(PlaylistEntry("songs/sub/b.flac", "B"), PlaylistEntry("songs/a.mp3", "A")),
            M3u.remapKeys(entries, map),
        )
    }

    @Test fun lookupIgnoresCase() {
        assertEquals("songs/a.mp3", M3u.remapKeys(listOf(PlaylistEntry("A.MP3")), map).single().key)
    }

    @Test fun unknownAndLegacyEntriesAreNeverDropped() {
        val entries = listOf(
            PlaylistEntry("outra/c.mp3"),
            PlaylistEntry("content://media/external/audio/media/9"),
            PlaylistEntry("/sdcard/x.mp3"),
        )
        assertEquals(entries, M3u.remapKeys(entries, map))
    }

    @Test fun movedPlaylistKeepsPointingAtTheSameSongs() {
        // Playlist estava na raiz, com caminhos relativos à raiz; foi movida para playlists/.
        val original = "#EXTM3U\n#EXTINF:100,Artista - A\na.mp3\nsub/b.flac\n"
        val rewritten = M3u.remapKeys(M3u.parse(original, playlistDir = ""), map)
        val text = M3u.write(rewritten, "playlists")
        val reparsed = M3u.parse(text, "playlists")
        assertEquals(listOf("songs/a.mp3", "songs/sub/b.flac"), reparsed.map { it.key })
        assertEquals("../songs/a.mp3", text.lines().first { it.endsWith("a.mp3") })
    }
}
