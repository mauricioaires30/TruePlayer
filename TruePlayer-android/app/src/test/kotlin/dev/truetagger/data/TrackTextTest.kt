package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTextTest {
    @Test fun albumComesBeforeArtist() {
        assertEquals(listOf("Pretti Loves U 2", "prettifun"), subtitleLines("Pretti Loves U 2", "prettifun"))
    }

    @Test fun blankLinesAreDropped() {
        assertEquals(listOf("prettifun"), subtitleLines("", "prettifun"))
        assertEquals(listOf("Pretti Loves U 2"), subtitleLines("Pretti Loves U 2", "  "))
        assertTrue(subtitleLines(null, null).isEmpty())
    }

    @Test fun whitespaceAroundNamesIsTrimmed() {
        assertEquals(listOf("A", "B"), subtitleLines("  A ", " B  "))
    }
}
