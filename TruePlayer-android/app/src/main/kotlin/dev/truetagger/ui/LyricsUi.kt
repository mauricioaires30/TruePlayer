package dev.truetagger.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.truetagger.android.LyricsRepository
import dev.truetagger.android.MusicPlaybackService
import dev.truetagger.android.PlayerSnapshot
import dev.truetagger.android.R
import dev.truetagger.android.t
import dev.truetagger.data.Lrc
import dev.truetagger.data.Lyrics
import dev.truetagger.data.extrapolatedPosition
import kotlinx.coroutines.delay

/** A linha acende um pouco antes do tempo marcado, como no Spotify: o olho precisa de um instante para achá-la. */
private const val LEAD_MS = 250L

private sealed interface LyricsLoad {
    data object Loading : LyricsLoad
    data object Empty : LyricsLoad
    data class Ready(val lyrics: Lyrics) : LyricsLoad
}

/** Posição da música em ms, estimada entre os avisos do player (que chegam espaçados) a cada 100 ms. */
@Composable
private fun rememberSmoothPositionMs(s: PlayerSnapshot): State<Long> {
    val position = remember { mutableLongStateOf(s.positionMs) }
    LaunchedEffect(s.positionMs, s.updatedAtMs, s.playing, s.durationMs, s.current?.id) {
        position.longValue = s.positionMs
        while (s.playing) {
            position.longValue = extrapolatedPosition(s.positionMs, s.updatedAtMs, SystemClock.elapsedRealtime(), true, s.durationMs)
            delay(100)
        }
    }
    return position
}

/**
 * Letra por cima da capa, que fica escura e (no Android 12+) desfocada por quem usa este componente. A letra é
 * procurada na internet só enquanto esta tela está aberta; sem internet ou sem letra, mostra um aviso discreto.
 */
@Composable
internal fun LyricsOverlay(s: PlayerSnapshot, modifier: Modifier = Modifier) {
    val track = s.current ?: return
    val load by produceState<LyricsLoad>(LyricsLoad.Loading, track.id) {
        value = LyricsLoad.Loading
        value = LyricsRepository.load(track)?.let { LyricsLoad.Ready(it) } ?: LyricsLoad.Empty
    }
    // Sem o desfoque (Android 11 ou menor) a capa fica mais escura para a letra continuar legível.
    val scrim = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.5f else 0.74f
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = scrim)), contentAlignment = Alignment.Center) {
        when (val state = load) {
            LyricsLoad.Loading -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            LyricsLoad.Empty -> Text(
                t(R.string.letra_indisponivel),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            is LyricsLoad.Ready -> LyricsBody(state.lyrics, s)
        }
    }
}

@Composable
private fun LyricsBody(lyrics: Lyrics, s: PlayerSnapshot) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A linha atual fica a 1/3 da altura, com espaço em cima e embaixo para as primeiras e últimas linhas chegarem lá.
        val focus = maxHeight / 3
        val focusPx = with(LocalDensity.current) { focus.roundToPx() }
        if (lyrics.isSynced) {
            val lines = lyrics.synced
            val position = rememberSmoothPositionMs(s)
            val active by remember(lines) { derivedStateOf { Lrc.activeIndex(lines, position.value + LEAD_MS) } }
            var placed by remember(lines) { mutableStateOf(false) }
            LaunchedEffect(lines, active) {
                if (active < 0) return@LaunchedEffect
                if (!placed) {
                    listState.scrollToItem(active, -focusPx)
                    placed = true
                } else if (!listState.isScrollInProgress) {
                    // Se a pessoa está rolando a letra, não puxa de volta; a próxima linha recoloca.
                    listState.animateScrollToItem(active, -focusPx)
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = focus),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines) { index, line ->
                    LyricLine(
                        text = line.text.ifBlank { "♪" },
                        relation = index.compareTo(active),
                        onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SEEK, line.timeMs) },
                    )
                }
            }
        } else {
            // Letra sem tempos: texto corrido, sem destaque.
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lyrics.plain) { line ->
                    Text(
                        line.ifBlank { " " },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
            }
        }
        // Bordas que somem, como no Spotify.
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
        // Crédito da fonte da letra, discreto, no canto.
        if (lyrics.source.isNotBlank()) {
            Text(
                lyrics.source,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

/** [relation]: negativo = já cantada, 0 = cantando agora, positivo = ainda vai ser cantada. */
@Composable
private fun LyricLine(text: String, relation: Int, onClick: () -> Unit) {
    val alpha by animateFloatAsState(
        when {
            relation == 0 -> 1f
            relation < 0 -> 0.38f
            else -> 0.55f
        },
        label = "lyricAlpha",
    )
    val scale by animateFloatAsState(if (relation == 0) 1f else 0.88f, label = "lyricScale")
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = alpha),
        modifier = Modifier
            .fillMaxWidth()
            // Tocar numa linha leva a música para ali.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    )
}
