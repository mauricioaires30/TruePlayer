package dev.truetagger.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.truetagger.android.PlayerSnapshot
import dev.truetagger.data.extrapolatedPosition
import dev.truetagger.data.waveY
import kotlin.math.min

/**
 * Relógio da onda: devolve a fase (0 a 1) andando enquanto [running]. Parado não gasta nada (sem quadros).
 * Quem usa deve ler o valor só na hora de desenhar, para a tela não recompor a cada quadro.
 */
@Composable
internal fun rememberWaveClock(running: Boolean, cycleMs: Int = 1600): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            phase.floatValue = (phase.floatValue + (now - last) / (cycleMs * 1_000_000f)) % 1f
            last = now
        }
    }
    return phase
}

/**
 * Fração tocada da música (0 a 1) que anda a cada quadro enquanto toca, em vez de dar um salto por segundo.
 * Leia `.value` só dentro do desenho (Canvas/drawBehind).
 */
@Composable
internal fun rememberSmoothFraction(s: PlayerSnapshot): State<Float> {
    val fraction = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(s.positionMs, s.updatedAtMs, s.playing, s.durationMs, s.current?.id) {
        fun at(positionMs: Long) = if (s.durationMs > 0L) (positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        fraction.floatValue = at(s.positionMs)
        if (s.playing && s.durationMs > 0L) {
            while (true) {
                androidx.compose.runtime.withFrameMillis {
                    fraction.floatValue = at(extrapolatedPosition(s.positionMs, s.updatedAtMs, SystemClock.elapsedRealtime(), true, s.durationMs))
                }
            }
        }
    }
    return fraction
}

/**
 * Barra de progresso ondulada (como a do Samsung): o trecho já tocado é uma onda que corre enquanto a música
 * toca e vai se achatando ao pausar; o resto é uma linha reta. [progress] é lido só no desenho.
 * Com [showThumb] desenha também uma barrinha vertical na posição atual (usada no player completo).
 */
@Composable
internal fun WavyProgressBar(
    progress: () -> Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = activeColor.copy(alpha = 0.22f),
    amplitude: Dp = 3.dp,
    wavelength: Dp = 26.dp,
    stroke: Dp = 3.dp,
    showThumb: Boolean = false,
) {
    // Amplitude suave: some ao pausar e volta ao tocar. Só anima quadros enquanto for preciso.
    val amount = animateFloatAsState(if (playing) 1f else 0f, tween(450), label = "waveAmount")
    val clock = rememberWaveClock(running = playing || amount.value > 0.01f)
    val path = remember { Path() }
    Canvas(modifier) {
        val width = size.width
        val centerY = size.height / 2f
        val strokePx = stroke.toPx()
        val thumbWidth = 4.dp.toPx()
        val x = progress().coerceIn(0f, 1f) * width
        val amplitudePx = amplitude.toPx() * amount.value
        val wavelengthPx = wavelength.toPx()

        // Resto da música: linha reta.
        drawLine(inactiveColor, Offset(x, centerY), Offset(width, centerY), strokePx, StrokeCap.Round)

        // Trecho tocado: onda. Perto do começo a onda cresce aos poucos, para a ponta esquerda não ficar tremendo.
        path.rewind()
        path.moveTo(0f, centerY)
        val step = 3.dp.toPx()
        var px = 0f
        while (px < x) {
            val envelope = min(1f, px / wavelengthPx)
            path.lineTo(px, centerY + waveY(px, -clock.value, amplitudePx, wavelengthPx) * envelope)
            px += step
        }
        path.lineTo(x, centerY + waveY(x, -clock.value, amplitudePx, wavelengthPx))
        drawPath(path, activeColor, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (showThumb) {
            val thumbX = x.coerceIn(thumbWidth / 2f, width - thumbWidth / 2f)
            val thumbHeight = size.height * 0.8f
            drawRoundRect(
                activeColor,
                topLeft = Offset(thumbX - thumbWidth / 2f, centerY - thumbHeight / 2f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2f),
            )
        }
    }
}

/**
 * Barra ondulada que também mexe na música: toque para ir a um ponto, ou arraste. Durante o arrasto a barra
 * segue o dedo (com a barrinha à mostra); o seek só é enviado ao soltar. Depois de soltar, fica no ponto
 * escolhido até o player confirmar a nova posição.
 */
@Composable
internal fun SeekableWavyBar(
    s: PlayerSnapshot,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    amplitude: Dp = 1.8.dp,
    wavelength: Dp = 20.dp,
    stroke: Dp = 2.dp,
) {
    val smooth = rememberSmoothFraction(s)
    var dragging by remember { mutableStateOf<Float?>(null) }
    var justSought by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(justSought) {
        if (justSought != null) {
            kotlinx.coroutines.delay(900)
            justSought = null
        }
    }
    val enabled = s.durationMs > 0L
    WavyProgressBar(
        progress = { dragging ?: justSought ?: smooth.value },
        playing = s.playing && dragging == null,
        showThumb = dragging != null,
        amplitude = amplitude,
        wavelength = wavelength,
        stroke = stroke,
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    justSought = f
                    onSeek(f)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = (offset.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging?.let { f ->
                            justSought = f
                            onSeek(f)
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                )
            },
    )
}
