package dev.truetagger.data

import dev.truetagger.model.AudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverNamesTest {
    private fun track(album: String, artist: String = "", name: String = "musica.mp3") =
        AudioTrack(id = "content://$name", displayName = name, title = "", artist = artist, album = album)

    @Test fun tracksOfTheSameAlbumShareOneCover() {
        val a = track("Pretti Loves U 2", "prettifun", "a.mp3")
        val b = track("pretti loves u 2", "Prettifun feat. X", "b.mp3")
        assertEquals(CoverNames.keyFor(a), CoverNames.keyFor(b))
        assertEquals("prettifun - pretti loves u 2", CoverNames.keyFor(a))
    }

    @Test fun sameAlbumNameByOtherArtistsIsNotMixed() {
        assertNotEquals(CoverNames.keyFor(track("Greatest Hits", "Queen")), CoverNames.keyFor(track("Greatest Hits", "ABBA")))
    }

    @Test fun aSongWithoutAlbumGetsItsOwnCover() {
        assertEquals("faixa - all love!", CoverNames.keyFor(track("", "x", "All Love!.mp3")))
        assertNotEquals(CoverNames.keyFor(track("", "", "um.mp3")), CoverNames.keyFor(track("", "", "dois.mp3")))
    }

    @Test fun namesAreSafeForAnyFileSystem() {
        val key = CoverNames.keyFor(track("AC/DC: Live? \"1\"", "AC/DC"))
        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').forEach { assertFalse("$it em $key", key.contains(it)) }
    }

    @Test fun longNamesAreCutButStayDistinct() {
        val a = CoverNames.keyFor(track("a".repeat(300) + "1", "x"))
        val b = CoverNames.keyFor(track("a".repeat(300) + "2", "x"))
        assertTrue(a.length <= 110)
        assertNotEquals(a, b)
    }

    @Test fun extensionComesFromTheImageHeader() {
        assertEquals("jpg", CoverNames.extensionFor(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", CoverNames.extensionFor(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals("webp", CoverNames.extensionFor("RIFF1234WEBPxxxx".toByteArray()))
        assertEquals("jpg", CoverNames.extensionFor(byteArrayOf(1, 2, 3)))
    }

    @Test fun groupingKeepsOneEntryPerCover() {
        val groups = CoverNames.firstTrackPerCover(
            listOf(track("A", "x", "1.mp3"), track("A", "x", "2.mp3"), track("B", "x", "3.mp3")),
        )
        assertEquals(2, groups.size)
        assertEquals(2, groups.getValue("x - a").size)
    }
}
