package dev.truetagger.data

/**
 * Cor que representa uma capa: média dos pixels (ARGB, como vêm de `Bitmap.getPixels`) em que as cores vivas
 * pesam mais que as acinzentadas e escuras, para o fundo do player não ficar sempre cinza. Devolve 0xRRGGBB.
 * Sem pixels devolve cinza escuro.
 */
fun dominantRgb(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0x202025
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var total = 0.0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val value = max / 255.0
        val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
        // Vivas e claras pesam mais; o pequeno piso mantém capas cinzas ou pretas com alguma cor.
        val weight = saturation * saturation * value + 0.02
        sumR += r * weight
        sumG += g * weight
        sumB += b * weight
        total += weight
    }
    val r = (sumR / total).toInt().coerceIn(0, 255)
    val g = (sumG / total).toInt().coerceIn(0, 255)
    val b = (sumB / total).toInt().coerceIn(0, 255)
    return (r shl 16) or (g shl 8) or b
}
