package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscNumbersTest {
    @Test
    fun discIndexReadsTagFormats() {
        assertEquals(0, discIndex(null))
        assertEquals(0, discIndex(""))
        assertEquals(0, discIndex("   "))
        assertEquals(0, discIndex("abc"))
        assertEquals(0, discIndex("0"))
        assertEquals(1, discIndex("1"))
        assertEquals(2, discIndex("2/3"))
        assertEquals(2, discIndex(" 2 / 3 "))
    }

    @Test
    fun mediaStoreDiscPrefersColumn() {
        assertEquals("2", mediaStoreDisc("2", 5))
        assertEquals("2", mediaStoreDisc("2/2", 2005))
    }

    @Test
    fun mediaStoreDiscFallsBackToPackedTrack() {
        assertEquals("1", mediaStoreDisc(null, 1005))
        assertEquals("2", mediaStoreDisc("", 2010))
    }

    @Test
    fun mediaStoreDiscEmptyWithoutDisc() {
        assertEquals("", mediaStoreDisc(null, 5))
        assertEquals("", mediaStoreDisc("", 0))
        assertEquals("", mediaStoreDisc("0", 12))
    }
}
