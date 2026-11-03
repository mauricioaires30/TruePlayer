package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** As letras destes testes são frases inventadas só para conferir o formato. */
class LyricsTest {
    @Test fun parsesTimesWithCentisecondsMillisecondsAndNoFraction() {
        val lines = Lrc.parse("[00:12.34] alfa\n[01:02.345] beta\n[02:00] gama\n")
        assertEquals(listOf(12_340L, 62_345L, 120_000L), lines.map { it.timeMs })
        assertEquals(listOf("alfa", "beta", "gama"), lines.map { it.text })
    }

    @Test fun oneLineWithSeveralTimesBecomesOneEntryForEach() {
        val lines = Lrc.parse("[00:30.00][01:10.00] refrão inventado\n[00:05.00] verso")
        assertEquals(listOf(5_000L, 30_000L, 70_000L), lines.map { it.timeMs })
        assertEquals("refrão inventado", lines[1].text)
        assertEquals("refrão inventado", lines[2].text)
    }

    @Test fun metadataTagsAndPlainLinesAreIgnoredAndOutputIsSorted() {
        val lines = Lrc.parse("[ar:Alguém]\n[ti:Algo]\n[length: 03:20]\nlinha sem tempo\n[00:20.00] depois\n[00:10.00] antes\n")
        assertEquals(listOf("antes", "depois"), lines.map { it.text })
    }

    @Test fun offsetShiftsEveryLine() {
        // offset positivo: a letra aparece mais cedo
        assertEquals(9_500L, Lrc.parse("[offset:+500]\n[00:10.00] a").single().timeMs)
        assertEquals(10_500L, Lrc.parse("[offset:-500]\n[00:10.00] a").single().timeMs)
        assertEquals(0L, Lrc.parse("[offset:+5000]\n[00:01.00] a").single().timeMs)
    }

    @Test fun blankLinesAreKeptAsGapsAndWordTagsAreStripped() {
        val lines = Lrc.parse("[00:05.00] \n[00:09.00] <00:09.00>duas <00:09.50>palavras\n")
        assertEquals("", lines[0].text)
        assertEquals("duas palavras", lines[1].text)
    }

    @Test fun activeIndexFollowsThePosition() {
        val lines = Lrc.parse("[00:10.00] a\n[00:20.00] b\n[00:30.00] c")
        assertEquals(-1, Lrc.activeIndex(lines, 0))
        assertEquals(-1, Lrc.activeIndex(lines, 9_999))
        assertEquals(0, Lrc.activeIndex(lines, 10_000))
        assertEquals(0, Lrc.activeIndex(lines, 19_999))
        assertEquals(1, Lrc.activeIndex(lines, 20_000))
        assertEquals(2, Lrc.activeIndex(lines, 600_000))
        assertEquals(-1, Lrc.activeIndex(emptyList(), 5_000))
    }

    private fun track(name: String, seconds: Int, synced: String = "[00:01.00] x", plain: String = "x", instrumental: Boolean = false) =
        LrcLibTrack(name, "Artista", "Álbum", seconds, instrumental, synced, plain)

    @Test fun bestPicksTheSyncedVersionWithACloseDuration() {
        val list = listOf(
            track("longa", 260),
            track("sem-tempo", 200, synced = ""),
            track("certa", 201),
            track("certa-mais-longe", 202),
        )
        assertEquals("certa", LrcLibParser.best(list, 200)?.trackName)
    }

    @Test fun bestFallsBackToPlainTextAndRejectsOtherVersions() {
        assertEquals("sem-tempo", LrcLibParser.best(listOf(track("sem-tempo", 200, synced = "")), 200)?.trackName)
        assertNull(LrcLibParser.best(listOf(track("longa", 260)), 200))
        assertNull(LrcLibParser.best(listOf(track("inst", 200, instrumental = true)), 200))
        // sem a duração do arquivo, vale a primeira com letra sincronizada
        assertNotNull(LrcLibParser.best(listOf(track("qualquer", 999)), 0))
    }

    @Test fun parsesTheGetAndSearchResponses() {
        val one = """{"id":1,"trackName":"Faixa","artistName":"Artista","albumName":"Álbum","duration":200.0,"instrumental":false,"plainLyrics":"um\ndois","syncedLyrics":"[00:01.00] um\n[00:02.00] dois"}"""
        val track = LrcLibParser.parseTrack(one)!!
        assertEquals(200, track.durationSec)
        val lyrics = LrcLibParser.lyricsOf(track)!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf("um", "dois"), lyrics.synced.map { it.text })
        assertTrue(lyrics.plain.isEmpty())

        assertEquals(2, LrcLibParser.parseSearch("[$one,$one]").size)
    }

    @Test fun plainOnlyLyricsKeepTheirLinesWithoutBlankEdges() {
        val track = track("t", 100, synced = "", plain = "\num\n\ndois\n")
        val lyrics = LrcLibParser.lyricsOf(track)!!
        assertFalse(lyrics.isSynced)
        assertEquals(listOf("um", "", "dois"), lyrics.plain)
    }

    @Test fun instrumentalOrEmptyOrBrokenResponsesGiveNothing() {
        assertNull(LrcLibParser.lyricsOf(track("t", 100, instrumental = true)))
        assertNull(LrcLibParser.parseTrack("""{"message":"not found","statusCode":404}"""))
        assertNull(LrcLibParser.parseTrack("não é json"))
        assertTrue(LrcLibParser.parseSearch("[]").isEmpty())
        assertTrue(LrcLibParser.parseSearch("{}").isEmpty())
    }
}
