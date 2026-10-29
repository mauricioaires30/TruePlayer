package dev.truetagger.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import dev.truetagger.data.waveY

/**
 * Barrinhas de equalizador (o símbolo de "tocando agora"). Tocando, sobem e descem em ritmos diferentes;
 * pausado, ficam paradas e baixas e não gastam quadros.
 */
@Composable
internal fun EqualizerBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (playing) AnimatedBars(color, modifier) else StaticBars(color, modifier)
}

private val barRest = floatArrayOf(0.35f, 0.6f, 0.45f, 0.3f)

@Composable
private fun AnimatedBars(color: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    // Cada barra com duração própria, para nunca ficarem em sincronia.
    val durations = intArrayOf(520, 740, 610, 880)
    val heights = durations.mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$index",
        )
    }
    Bars(color, modifier) { index -> heights[index].value }
}

@Composable
private fun StaticBars(color: Color, modifier: Modifier) {
    Bars(color, modifier) { index -> barRest[index] }
}

@Composable
private fun Bars(color: Color, modifier: Modifier, height: (Int) -> Float) {
    Canvas(modifier.then(Modifier.size(22.dp))) {
        val count = barRest.size
        val gap = size.width * 0.1f
        val barWidth = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val h = size.height * height(i).coerceIn(0.1f, 1f)
            drawRoundRect(
                color,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/**
 * Ondas suaves passando atrás do conteúdo do item da música atual. Só existe enquanto toca: a amplitude
 * desce a zero ao pausar. As ondas são desenhadas atrás do texto (com pouca opacidade para o texto continuar
 * legível). Aplique depois do `clip` e do `background` do item.
 */
@Composable
internal fun Modifier.nowPlayingWaves(playing: Boolean, color: Color): Modifier {
    val amount = animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "wavesAmount")
    val clock = rememberWaveClock(running = playing || amount.value > 0.01f, cycleMs = 5200)
    val path = remember { Path() }
    // As camadas só mudam com o tamanho do item; o desenho de cada quadro só lê a fase e a amplitude.
    return drawWithCache {
        val height = size.height
        val width = size.width
        val step = 6.dp.toPx()
        // Três camadas: comprimentos, velocidades e sentidos diferentes dão o efeito de ondas se cruzando.
        val layers = listOf(
            Layer(0.62f, 0.14f * height, 140.dp.toPx(), 1f, 0.00f, 0.10f),
            Layer(0.72f, 0.11f * height, 96.dp.toPx(), -1.6f, 0.30f, 0.08f),
            Layer(0.82f, 0.09f * height, 70.dp.toPx(), 2.3f, 0.60f, 0.06f),
        )
        onDrawBehind {
        val a = amount.value
        if (a <= 0.01f) return@onDrawBehind
        for (layer in layers) {
            path.rewind()
            val baseY = height * layer.level
            path.moveTo(0f, height)
            var x = 0f
            while (x <= width + step) {
                path.lineTo(x, baseY + waveY(x, clock.value * layer.speed + layer.offset, layer.amplitude * a, layer.length))
                x += step
            }
            path.lineTo(width, height)
            path.close()
            drawPath(path, color.copy(alpha = layer.alpha * a))
        }
        }
    }
}

private class Layer(val level: Float, val amplitude: Float, val length: Float, val speed: Float, val offset: Float, val alpha: Float)
