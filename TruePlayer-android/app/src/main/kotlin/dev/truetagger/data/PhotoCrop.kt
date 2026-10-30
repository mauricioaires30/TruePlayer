package dev.truetagger.data

/*
 * Conta do ajuste de foto: a imagem é desenhada com o centro dela no centro da janela quadrada (lado [view]),
 * deslocada por um "pan", e ampliada por [coverScale] (a menor escala em que ela cobre a janela) vezes o zoom do usuário.
 */

/** Menor escala em que uma imagem de [width] x [height] cobre inteira uma janela quadrada de lado [view]. */
fun coverScale(width: Int, height: Int, view: Float): Float = view / minOf(width, height).coerceAtLeast(1)

/** Quanto a imagem pode andar de cada lado do centro, em um eixo, sem deixar a janela com espaço vazio. */
fun maxPan(imageSide: Int, scale: Float, view: Float): Float = ((imageSide * scale - view) / 2f).coerceAtLeast(0f)

/** Prende o deslocamento [offset] (em um eixo) ao que a imagem permite. */
fun clampPan(offset: Float, imageSide: Int, scale: Float, view: Float): Float {
    val limit = maxPan(imageSide, scale, view)
    return offset.coerceIn(-limit, limit)
}
