package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    @Test fun splitsArtistAndTitleOnTheDash() {
        val p = TitleParser.parse("Playboi Carti - Sky")
        assertEquals("Playboi Carti", p.artist)
        assertEquals("Sky", p.title)
    }

    @Test fun removesOfficialVideoNoise() {
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa (Official Music Video)").title)
        assertEquals("Faixa", TitleParser.parse("Faixa [Official Audio]").title)
        assertEquals("Faixa", TitleParser.parse("Faixa | Official Video").title)
    }

    @Test fun versionAndLeakMarkersAreRemovedFromTheTitle() {
        assertEquals("Whole Lotta Red", TitleParser.parse("Playboi Carti - Whole Lotta Red v2").title)
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa [LEAK]").title)
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa (V1) (prod. Fulano)").title)
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa | LEAK").title)
    }

    @Test fun featuringIsDroppedFromTheTitle() {
        assertEquals("Faixa", TitleParser.parse("Artista - Faixa (feat. Outro)").title)
    }

    @Test fun titleWithoutDashHasNoArtist() {
        val p = TitleParser.parse("So a faixa")
        assertNull(p.artist)
        assertEquals("So a faixa", p.title)
    }

    @Test fun normalTitlesAreLeftAlone() {
        assertEquals("Dev", TitleParser.parse("Volume 2 - Dev").title)
        assertEquals("TV 2 Theme", TitleParser.parse("TV 2 Theme").title)
        assertEquals("Vogue", TitleParser.parse("Artista - Vogue").title)
    }

    @Test fun variantsAreReported() {
        assertTrue("remix" in TitleParser.parse("Artista - Faixa (Remix)").variants)
        assertTrue("live" in TitleParser.parse("Artista - Faixa (Live)").variants)
        assertTrue(TitleParser.parse("Artista - Faixa").variants.isEmpty())
    }
}
