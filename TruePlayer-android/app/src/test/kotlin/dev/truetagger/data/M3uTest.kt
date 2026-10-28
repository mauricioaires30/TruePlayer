package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    @Test fun writeUsesPathRelativeToPlaylistsDir() {
        val text = M3u.write(listOf(PlaylistEntry("songs/a.mp3", "Artista - A", 120)))
        assertEquals("#EXTM3U\n#EXTINF:120,Artista - A\n../songs/a.mp3\n", text)
    }

    @Test fun roundTripPreservesOrderAndLabels() {
        val entries = listOf(
            PlaylistEntry("songs/z.mp3", "Z", 10),
            PlaylistEntry("songs/Sub/a.mp3", "A", 20),
            PlaylistEntry("songs/m.mp3", "", -1),
        )
        assertEquals(entries, M3u.parse(M3u.write(entries)))
    }

    @Test fun legacyAbsolutePathIsConvertedWhenRootIsKnown() {
        val text = "#EXTM3U\n/storage/emulated/0/Music/TruePlayer/songs/a.mp3\n"
        val entries = M3u.parse(text, rootRelativePath = "Music/TruePlayer/")
        assertEquals("songs/a.mp3", entries.single().key)
        assertTrue(entries.single().isPortable)
    }

    @Test fun legacyEntriesWeCannotConvertAreKept() {
        val text = "content://media/external/audio/media/1234\n/sdcard/Outro/lugar.mp3\n"
        val entries = M3u.parse(text)
        assertEquals(2, entries.size)
        assertFalse(entries[0].isPortable)
        assertFalse(entries[1].isPortable)
        assertEquals(text, M3u.write(entries).removePrefix("#EXTM3U\n"))
    }

    @Test fun percentEncodedAndFileUrisAreDecoded() {
        val entries = M3u.parse("file:///x/Music/T/songs/Fa%C3%A7a%20um.mp3\n", rootRelativePath = "Music/T")
        assertEquals("songs/Faça um.mp3", entries.single().key)
    }

    @Test fun bomAndBlankLinesAreIgnored() {
        val entries = M3u.parse("﻿#EXTM3U\n\n  \n../songs/a.mp3\r\n")
        assertEquals(listOf("songs/a.mp3"), entries.map { it.key })
    }
}
