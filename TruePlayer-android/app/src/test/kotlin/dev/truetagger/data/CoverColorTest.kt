package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverColorTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF

    @Test fun aSolidColorIsItself() {
        val rgb = dominantRgb(IntArray(64) { argb(200, 40, 40) })
        // A média em ponto flutuante pode cair um abaixo do valor exato.
        assertEquals(200.0, r(rgb).toDouble(), 1.0)
        assertEquals(40.0, g(rgb).toDouble(), 1.0)
        assertEquals(40.0, b(rgb).toDouble(), 1.0)
    }

    @Test fun vividColorsBeatGrayAndBlackBackgrounds() {
        // 90% preto/cinza e 10% azul vivo: o resultado tem que puxar para o azul.
        val pixels = IntArray(100) { if (it < 10) argb(20, 60, 230) else if (it < 50) argb(18, 18, 18) else argb(120, 120, 120) }
        val rgb = dominantRgb(pixels)
        assertTrue(b(rgb) > r(rgb) + 40)
    }

    @Test fun grayCoversStayGray() {
        val rgb = dominantRgb(IntArray(16) { argb(90, 90, 90) })
        assertEquals(90.0, r(rgb).toDouble(), 1.0)
        assertEquals(90.0, g(rgb).toDouble(), 1.0)
        assertEquals(90.0, b(rgb).toDouble(), 1.0)
    }

    @Test fun noPixelsGivesADarkGray() {
        assertEquals(0x202025, dominantRgb(IntArray(0)))
    }
}
