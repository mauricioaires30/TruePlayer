package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCropTest {
    @Test fun coverScaleFitsTheShorterSide() {
        assertEquals(0.5f, coverScale(1000, 600, 300f) / 1f * 1f, 0.0001f) // 300 / 600
        assertEquals(0.3f, coverScale(1000, 2000, 300f), 0.0001f)
    }

    @Test fun aSquareImageAtMinimumZoomCannotMove() {
        val k = coverScale(500, 500, 300f)
        assertEquals(0f, maxPan(500, k, 300f), 0.0001f)
        assertEquals(0f, clampPan(40f, 500, k, 300f), 0.0001f)
    }

    @Test fun aWideImageMovesOnlyAlongItsLongSide() {
        val k = coverScale(1000, 500, 300f)
        assertEquals(0f, maxPan(500, k, 300f), 0.0001f)
        assertEquals(150f, maxPan(1000, k, 300f), 0.0001f)
        assertEquals(150f, clampPan(900f, 1000, k, 300f), 0.0001f)
        assertEquals(-150f, clampPan(-900f, 1000, k, 300f), 0.0001f)
        assertEquals(20f, clampPan(20f, 1000, k, 300f), 0.0001f)
    }

    @Test fun zoomingInAllowsMoreMovement() {
        val k = coverScale(500, 500, 300f) * 2f
        assertEquals(150f, maxPan(500, k, 300f), 0.0001f)
    }
}
