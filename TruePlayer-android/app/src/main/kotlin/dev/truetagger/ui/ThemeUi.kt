package dev.truetagger.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tipografia única do app: títulos mais firmes, corpo de leitura confortável e rótulos pequenos legíveis.
 * Mantém a família padrão do sistema; só ajusta tamanho, peso e espaçamento entre linhas.
 */
internal val TruePlayerTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
    )
}

/** Cantos do app: 8 / 12 / 16 / 24 dp (diálogos usam o maior). */
internal val TruePlayerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Tema do TruePlayer: cores, tipografia e formas. Use em toda tela e diálogo. */
@Composable
internal fun TruePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TruePlayerDarkScheme,
        typography = TruePlayerTypography,
        shapes = TruePlayerShapes,
        content = content,
    )
}

/**
 * Nome de música, álbum, artista, playlist ou gênero em uma linha. Se não cabe, rola em loop para a esquerda
 * (como no Spotify): espera um pouco, anda, dá um espaço e recomeça. Se cabe, fica parado. Rola sozinho e não
 * reage a toque.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScrollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Start,
) {
    if (text.isBlank()) return
    // O Box deixa o texto centralizado quando cabe (o marquee mede o texto sem limite de largura).
    Box(modifier, contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
        Text(
            text = text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            // Só animação: não responde a toque nem a foco (o toque segue para a linha) e começa sozinho
            // enquanto o nome está na tela. Fora da tela (lista rolada) o item nem existe, então não anima.
            modifier = Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                animationMode = MarqueeAnimationMode.Immediately,
                initialDelayMillis = 1_500,
                repeatDelayMillis = 1_500,
                spacing = MarqueeSpacing(32.dp),
                velocity = 30.dp,
            ),
        )
    }
}

/** "1 música", "3 músicas". */
internal fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

/** Tela vazia: círculo com ícone, título e uma frase dizendo o que fazer. */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = UiTokens.space6, vertical = UiTokens.space6 * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UiTokens.space2),
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
