package dev.truetagger.android

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsQueryTest {
    @Test fun primaryArtistKeepsOnlyTheFirstOne() {
        assertEquals("A", LyricsRepository.primaryArtist("A, B"))
        assertEquals("A", LyricsRepository.primaryArtist("A; B"))
        assertEquals("A", LyricsRepository.primaryArtist("A & B"))
        assertEquals("A", LyricsRepository.primaryArtist("A feat. B"))
        assertEquals("A", LyricsRepository.primaryArtist("A ft B"))
        assertEquals("Solo", LyricsRepository.primaryArtist("Solo"))
        assertEquals("", LyricsRepository.primaryArtist(""))
    }

    @Test fun cleanTitleDropsFeaturingAndVersionSuffixes() {
        assertEquals("Faixa", LyricsRepository.cleanTitle("Faixa (feat. Alguém)"))
        assertEquals("Faixa", LyricsRepository.cleanTitle("Faixa [Remastered]"))
        assertEquals("Faixa", LyricsRepository.cleanTitle("Faixa - Remastered 2011"))
        assertEquals("Faixa", LyricsRepository.cleanTitle("Faixa"))
    }
}
