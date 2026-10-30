package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMathTest {
    @Test fun waveStartsAtZeroAndPeaksAtAQuarterOfTheWavelength() {
        assertEquals(0f, waveY(0f, 0f, 3f, 24f), 0.001f)
        assertEquals(3f, waveY(6f, 0f, 3f, 24f), 0.001f)
        assertEquals(-3f, waveY(18f, 0f, 3f, 24f), 0.001f)
    }

    @Test fun waveRepeatsEveryWavelengthAndPhaseSlidesIt() {
        assertEquals(waveY(5f, 0f, 2f, 20f), waveY(25f, 0f, 2f, 20f), 0.001f)
        // fase de um quarto de volta equivale a andar um quarto de onda
        assertEquals(waveY(3f + 5f, 0f, 2f, 20f), waveY(3f, 0.25f, 2f, 20f), 0.001f)
    }

    @Test fun zeroAmplitudeOrWavelengthIsAFlatLine() {
        assertEquals(0f, waveY(7f, 0.3f, 0f, 20f), 0f)
        assertEquals(0f, waveY(7f, 0.3f, 3f, 0f), 0f)
    }

    @Test fun positionAdvancesWithTheClockWhilePlaying() {
        assertEquals(10_500L, extrapolatedPosition(10_000L, 1_000L, 1_500L, playing = true, durationMs = 200_000L))
    }

    @Test fun pausedOrUnknownTimestampKeepsThePositionAsIs() {
        assertEquals(10_000L, extrapolatedPosition(10_000L, 1_000L, 1_900L, playing = false, durationMs = 200_000L))
        assertEquals(10_000L, extrapolatedPosition(10_000L, 0L, 5_000L, playing = true, durationMs = 200_000L))
    }

    @Test fun estimateStopsAfterTwoSecondsWithoutANewUpdate() {
        assertEquals(10_000L + MAX_EXTRAPOLATION_MS, extrapolatedPosition(10_000L, 1_000L, 60_000L, playing = true, durationMs = 200_000L))
    }

    @Test fun neverGoesPastTheEndOrBackwards() {
        assertEquals(100_000L, extrapolatedPosition(99_500L, 1_000L, 2_900L, playing = true, durationMs = 100_000L))
        assertEquals(10_000L, extrapolatedPosition(10_000L, 5_000L, 4_000L, playing = true, durationMs = 200_000L))
        assertTrue(extrapolatedPosition(10_000L, 1_000L, 1_100L, playing = true, durationMs = 0L) > 10_000L)
    }

    @Test fun touchesMapToTheTrackBetweenTheDotLimits() {
        assertEquals(0f, seekFraction(2f, 200f, 10f), 0f)
        assertEquals(0f, seekFraction(10f, 200f, 10f), 0f)
        assertEquals(0.5f, seekFraction(100f, 200f, 10f), 0.001f)
        assertEquals(1f, seekFraction(190f, 200f, 10f), 0f)
        assertEquals(1f, seekFraction(400f, 200f, 10f), 0f)
        assertEquals(0f, seekFraction(5f, 10f, 10f), 0f)
    }

    @Test fun durationsUseMinutesAndSecondsAndHoursWhenNeeded() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:08", formatDuration(8_900L))
        assertEquals("2:47", formatDuration(167_000L))
        assertEquals("1:02:09", formatDuration(3_729_000L))
        assertEquals("0:00", formatDuration(-5_000L))
    }
}
