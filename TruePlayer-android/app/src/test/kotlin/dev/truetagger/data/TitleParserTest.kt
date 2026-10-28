package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    @Test fun splitsArtistAndTitleOnTheDash() {
        val p = TitleParser.parse("Playboi Carti - Sky")
        assertEquals("Playboi Carti", p.artist)
        assertEquals("Sky", p.title)
        assertFalse(p.unreleased)
        assertNull(p.version)
    }

    @Test fun removesOfficialVideoNoise() {
        val p = TitleParser.parse("Artista - Faixa (Official Music Video)")
        assertEquals("Faixa", p.title)
        assertFalse(p.unreleased)
        assertEquals("Faixa", TitleParser.parse("Faixa [Official Audio]").title)
        assertEquals("Faixa", TitleParser.parse("Faixa | Official Video").title)
    }

    @Test fun versionNumberIsSeparatedFromTheTitle() {
        val p = TitleParser.parse("Playboi Carti - Whole Lotta Red v2")
        assertEquals("Playboi Carti", p.artist)
        assertEquals("Whole Lotta Red", p.title)
        assertEquals("v2", p.version)
        assertTrue(p.unreleased)
    }

    @Test fun bracketedLeakMarkersAreRemovedAndDetected() {
        val p = TitleParser.parse("Artista - Faixa [LEAK]")
        assertEquals("Faixa", p.title)
        assertEquals("Leak", p.version)
        assertTrue(p.unreleased)
        val q = TitleParser.parse("Artista - Faixa (V1) (prod. Fulano)")
        assertEquals("Faixa", q.title)
        assertEquals("v1", q.version)
    }

    @Test fun leakAfterABarStillCountsAsUnreleased() {
        val p = TitleParser.parse("Artista - Faixa | LEAK")
        assertEquals("Faixa", p.title)
        assertTrue(p.unreleased)
    }

    @Test fun featuringIsDroppedFromTheTitle() {
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa (feat. Outro)").title)
    }

    @Test fun titleWithoutDashHasNoArtist() {
        val p = TitleParser.parse("So a faixa")
        assertNull(p.artist)
        assertEquals("So a faixa", p.title)
    }

    @Test fun normalTitlesAreNotMarkedAsUnreleased() {
        assertFalse(TitleParser.parse("Volume 2 - Dev").unreleased)
        assertFalse(TitleParser.parse("TV 2 Theme").unreleased)
        assertFalse(TitleParser.parse("Artista - Vogue").unreleased)
    }

    @Test fun variantsAreReported() {
        assertTrue("remix" in TitleParser.parse("Artista - Faixa (Remix)").variants)
        assertTrue("live" in TitleParser.parse("Artista - Faixa (Live)").variants)
        assertTrue(TitleParser.parse("Artista - Faixa").variants.isEmpty())
    }
}
