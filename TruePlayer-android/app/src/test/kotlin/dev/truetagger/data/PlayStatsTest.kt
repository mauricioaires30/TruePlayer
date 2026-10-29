package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlayStatsTest {
    private val today = LocalDate.of(2026, 10, 27)

    private fun stats() = PlayStats().apply {
        add(today, "songs/a.mp3", 120_000, 1)
        add(today.minusDays(6), "songs/a.mp3", 60_000, 1) // ainda na semana
        add(today.minusDays(7), "songs/a.mp3", 30_000, 1) // fora da semana, dentro do mês
        add(today.minusDays(40), "songs/b.mp3", 300_000, 2) // fora do mês
        add(today.minusDays(400), "songs/c.mp3", 10_000, 1) // fora do ano
        addLegacy("songs/a.mp3", 50)
    }

    @Test fun weekCoversTodayAndTheSixPreviousDays() {
        val week = stats().totals(StatsRange.WEEK, today)
        assertEquals(StatValue(180_000, 2), week["songs/a.mp3"])
        assertNull(week["songs/b.mp3"])
    }

    @Test fun monthIncludesDay29AndExcludesDay40() {
        val month = stats().totals(StatsRange.MONTH, today)
        assertEquals(StatValue(210_000, 3), month["songs/a.mp3"])
        assertNull(month["songs/b.mp3"])
    }

    @Test fun yearExcludesOlderThan365Days() {
        val year = stats().totals(StatsRange.YEAR, today)
        assertEquals(300_000L, year["songs/b.mp3"]?.ms)
        assertNull(year["songs/c.mp3"])
    }

    @Test fun allTimeIncludesEverythingAndLegacyPlays() {
        val all = stats().totals(StatsRange.ALL, today)
        assertEquals(StatValue(210_000, 53), all["songs/a.mp3"])
        assertEquals(10_000L, all["songs/c.mp3"]?.ms)
    }

    @Test fun legacyPlaysOnlyCountInAllTime() {
        assertEquals(StatValue(210_000, 3), stats().totals(StatsRange.SIX_MONTHS, today)["songs/a.mp3"])
    }

    @Test fun minutesAreWholeMinutes() {
        assertEquals(2L, StatValue(179_999, 0).minutes)
    }

    @Test fun jsonRoundTripKeepsEverything() {
        val original = stats()
        val parsed = PlayStats.parse(original.toJson())
        assertNotNull(parsed)
        assertTrue(original.sameContentAs(parsed!!))
    }

    @Test fun invalidJsonIsNotAStatsFile() {
        assertNull(PlayStats.parse("isso não é json"))
    }

    @Test fun newerSchemaIsReadButFlagged() {
        val parsed = PlayStats.parse("""{"schemaVersion": 99, "days": {}, "campoNovo": 1}""")
        assertNotNull(parsed)
        assertTrue(parsed!!.futureSchema)
        assertFalse(PlayStats().futureSchema)
    }

    @Test fun mergeTakesTheLargerValueSoNothingIsCountedTwice() {
        val local = PlayStats().apply { add(today, "songs/a.mp3", 100_000, 2) }
        val remote = PlayStats().apply {
            add(today, "songs/a.mp3", 90_000, 3)
            add(today, "songs/b.mp3", 10_000, 1)
        }
        local.mergeMax(remote)
        val totals = local.totals(StatsRange.WEEK, today)
        assertEquals(StatValue(100_000, 3), totals["songs/a.mp3"])
        assertEquals(StatValue(10_000, 1), totals["songs/b.mp3"])
        local.mergeMax(remote) // repetir não muda nada
        assertEquals(StatValue(100_000, 3), local.totals(StatsRange.WEEK, today)["songs/a.mp3"])
    }

    @Test fun blankKeysAndEmptyAmountsAreIgnored() {
        val s = PlayStats()
        s.add(today, "", 1000, 1)
        s.add(today, "songs/a.mp3", 0, 0)
        assertTrue(s.isEmpty)
    }

    @Test fun clearRemovesEverythingIncludingLegacyPlays() {
        val s = stats()
        s.clear()
        assertTrue(s.isEmpty)
        assertTrue(s.totals(StatsRange.ALL, today).isEmpty())
    }

    @Test fun totalForOneDay() {
        assertEquals(120_000L, stats().totalMsOn(today))
        assertEquals(0L, stats().totalMsOn(today.minusDays(1)))
    }

    @Test fun allTimeIsTheFirstRange() {
        assertEquals(StatsRange.ALL, StatsRange.entries.first())
    }

    private val ranked = listOf(
        "long" to StatValue(600_000, 2),
        "often" to StatValue(120_000, 9),
        "tie b" to StatValue(120_000, 3),
        "tie a" to StatValue(120_000, 3),
        "old" to StatValue(0, 4),
    )

    private fun StatsMetric.names() = ranked.filter { counts(it.second) }.sortedWith(order({ it.second }, { it.first })).map { it.first }

    @Test fun minutesSortByTimeThenPlaysThenName() {
        assertEquals(listOf("long", "often", "tie a", "tie b", "old"), StatsMetric.MINUTES.names())
    }

    @Test fun playsSortByCountThenTime() {
        assertEquals(listOf("often", "old", "tie a", "tie b", "long"), StatsMetric.PLAYS.names())
    }

    @Test fun playsSkipSongsThatWereNeverStarted() {
        assertFalse(StatsMetric.PLAYS.counts(StatValue(90_000, 0)))
        assertTrue(StatsMetric.MINUTES.counts(StatValue(0, 1)))
    }
}
