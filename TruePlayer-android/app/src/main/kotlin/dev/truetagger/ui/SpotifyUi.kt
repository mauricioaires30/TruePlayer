package dev.truetagger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.truetagger.android.R
import dev.truetagger.android.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/*
 * Peças do visual "Spotify": botão redondo de tocar com o aleatório ao lado, cabeçalho grande das telas de detalhe
 * (álbum, artista, gênero, playlist) e os blocos coloridos dos gêneros.
 */

/** Aleatório (ícone simples) e Tocar (círculo cheio na cor de destaque), sempre juntos, nesta ordem. */
@Composable
internal fun PlayControls(enabled: Boolean, onShuffle: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onShuffle, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Shuffle, t(R.string.aleatorio), Modifier.size(26.dp))
        }
        // Só o triângulo na cor de destaque, sem círculo em volta (o círculo roxo cobria o começo da lista).
        IconButton(onClick = onPlay, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.PlayArrow, t(R.string.tocar), Modifier.size(48.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.38f))
        }
    }
}

/** Cor do fundo do cabeçalho a partir da cor da capa: escurecida no tema escuro e clareada no claro, para o texto ler bem. */
@Composable
internal fun heroBackdropColor(base: Color): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) lerp(base, Color.White, 0.45f) else lerp(base, Color.Black, 0.5f)

/**
 * Cabeçalho das telas de detalhe: capa grande no meio (ou nada, no gênero), título grande, linha de apoio e,
 * embaixo, as informações à esquerda e os botões de tocar à direita. Vai como primeiro item da lista e rola junto.
 */
@Composable
internal fun DetailHero(
    title: String,
    meta: String,
    playEnabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    cover: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(top = 52.dp, bottom = UiTokens.space2)) {
        if (cover != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cover() }
            Spacer(Modifier.height(UiTokens.space4))
        } else {
            Spacer(Modifier.height(UiTokens.space6))
        }
        ScrollingText(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.invoke()
        Row(Modifier.fillMaxWidth().padding(top = UiTokens.space1), verticalAlignment = Alignment.CenterVertically) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PlayControls(playEnabled, onShuffle, onPlay)
        }
    }
}

private val GenrePalette = listOf(
    Color(0xFF8D1F3B), Color(0xFF1E5C8C), Color(0xFF2F7D4F), Color(0xFF7A4BB0), Color(0xFFB0561E), Color(0xFF1F7A7A),
    Color(0xFFA03A82), Color(0xFF4A5FC1), Color(0xFF6B7F1F), Color(0xFFB03030), Color(0xFF3F6E9E), Color(0xFF8C5A2B),
)

/** Cor fixa de um gênero (ou playlist): o mesmo nome dá sempre a mesma cor. */
internal fun genreColor(name: String): Color = GenrePalette[abs(name.trim().lowercase().hashCode() % GenrePalette.size)]

/** Bloco colorido de um gênero, como a grade "Navegar" do Spotify: nome em cima, quantidade embaixo. */
@Composable
internal fun GenreTile(name: String, songs: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .background(genreColor(name))
            .clickable(onClick = onClick)
            .padding(UiTokens.space3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(songs, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
    }
}

/** Foto da playlist (escolhida ou vinda do link), ou null se ela não tem. Recarrega quando a foto muda. */
@Composable
internal fun rememberPlaylistCover(name: String): android.graphics.Bitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by dev.truetagger.android.PlaylistCovers.revision.collectAsState()
    val bitmap by produceState<android.graphics.Bitmap?>(null, name, revision) {
        value = withContext(Dispatchers.IO) { dev.truetagger.android.PlaylistCovers.load(context, name) }
    }
    return bitmap
}

/** Quadrado da playlist: a foto dela ou, sem foto, o quadrado colorido com o ícone. */
@Composable
internal fun PlaylistTile(name: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val photo = rememberPlaylistCover(name)
    if (photo != null) {
        Image(
            photo.asImageBitmap(),
            contentDescription = name,
            modifier = modifier.size(size).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        ColorTile(name, size, Icons.AutoMirrored.Rounded.QueueMusic, modifier)
    }
}

/** Quadrado colorido com um ícone, no lugar da capa de playlists (que não têm imagem própria). */
@Composable
internal fun ColorTile(name: String, size: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(4.dp)).background(genreColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}
