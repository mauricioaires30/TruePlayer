package dev.truetagger.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.truetagger.android.R
import dev.truetagger.android.t
import dev.truetagger.data.clampPan
import dev.truetagger.data.coverScale

private const val OUTPUT_SIDE = 800
private const val MAX_ZOOM = 5f

/**
 * Ajuste da foto antes de virar a foto da playlist: gira de 90 em 90 graus, aproxima (pinça ou barra) e arrasta
 * para escolher o trecho que aparece no quadrado. Devolve a imagem quadrada já recortada.
 */
@Composable
internal fun PhotoAdjustDialog(source: Bitmap, onSave: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    var image by remember { mutableStateOf(source) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val viewDp = 280.dp
    val viewPx = with(LocalDensity.current) { viewDp.toPx() }

    fun clamp(offset: Offset, zoomLevel: Float): Offset {
        val scale = coverScale(image.width, image.height, viewPx) * zoomLevel
        return Offset(clampPan(offset.x, image.width, scale, viewPx), clampPan(offset.y, image.height, scale, viewPx))
    }

    fun rotate(degrees: Float) {
        val matrix = Matrix().apply { postRotate(degrees) }
        image = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
        zoom = 1f
        pan = Offset.Zero
    }

    fun render(): Bitmap {
        val out = Bitmap.createBitmap(OUTPUT_SIDE, OUTPUT_SIDE, Bitmap.Config.ARGB_8888)
        val factor = OUTPUT_SIDE / viewPx
        val scale = coverScale(image.width, image.height, viewPx) * zoom * factor
        val matrix = Matrix().apply {
            postTranslate(-image.width / 2f, -image.height / 2f)
            postScale(scale, scale)
            postTranslate(OUTPUT_SIDE / 2f + pan.x * factor, OUTPUT_SIDE / 2f + pan.y * factor)
        }
        android.graphics.Canvas(out).drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(R.string.playlist_foto_ajustar)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(UiTokens.space2)) {
                Canvas(
                    Modifier
                        .size(viewDp)
                        .clip(RoundedCornerShape(UiTokens.songItemRadius))
                        .background(Color.Black)
                        .pointerInput(image) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                pan = clamp(pan + panChange, zoom)
                            }
                        },
                ) {
                    val scale = coverScale(image.width, image.height, size.width) * zoom
                    withTransform({
                        translate(size.width / 2f + pan.x, size.height / 2f + pan.y)
                        scale(scale, scale, pivot = Offset.Zero)
                    }) {
                        drawImage(image.asImageBitmap(), topLeft = Offset(-image.width / 2f, -image.height / 2f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.space1)) {
                    IconButton(onClick = { rotate(-90f) }) { Icon(Icons.Rounded.RotateLeft, t(R.string.playlist_foto_girar)) }
                    IconButton(onClick = { rotate(90f) }) { Icon(Icons.Rounded.RotateRight, t(R.string.playlist_foto_girar)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ZoomIn, null)
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it; pan = clamp(pan, it) },
                        valueRange = 1f..MAX_ZOOM,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(render()) }) { Text(t(R.string.salvar)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(R.string.cancelar)) } },
    )
}
