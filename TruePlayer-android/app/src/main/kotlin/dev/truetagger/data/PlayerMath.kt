package dev.truetagger.data

import kotlin.math.PI
import kotlin.math.sin

/**
 * Altura da onda no ponto [x]. [phase] é em voltas (0 a 1, dá a volta completa); [wavelength] usa a mesma
 * unidade de [x]. Com comprimento de onda zero (ou negativo) a onda fica reta.
 */
fun waveY(x: Float, phase: Float, amplitude: Float, wavelength: Float): Float =
    if (wavelength <= 0f) 0f else amplitude * sin(((x / wavelength) + phase) * 2f * PI.toFloat())

/**
 * Posição estimada da música agora. O player só avisa a posição a cada ~1 s; entre um aviso e outro a barra
 * continua andando com o relógio, e por isso a estimativa para depois de [MAX_EXTRAPOLATION_MS] sem aviso novo
 * (se o player parou de responder, a barra não corre sozinha). Pausado, devolve a posição como veio.
 */
fun extrapolatedPosition(positionMs: Long, snapshotAtMs: Long, nowMs: Long, playing: Boolean, durationMs: Long): Long {
    if (!playing || snapshotAtMs <= 0L) return positionMs
    val estimated = positionMs + (nowMs - snapshotAtMs).coerceIn(0L, MAX_EXTRAPOLATION_MS)
    return if (durationMs > 0L) estimated.coerceAtMost(durationMs) else estimated
}

const val MAX_EXTRAPOLATION_MS = 2_000L

/** Tempo da música como "3:07", ou "1:02:09" a partir de uma hora. Negativo vira zero. */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}
