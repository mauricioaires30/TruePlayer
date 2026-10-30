package dev.truetagger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.truetagger.android.t
import dev.truetagger.android.tp
import dev.truetagger.android.R

/**
 * Geometria única do app.
 *
 * Todos os valores derivam de uma grade de 4dp. A ordem e a posição relativa dos
 * componentes continuam exatamente as mesmas; apenas margens, alturas e
 * espaçamentos foram normalizados para que todas as telas usem a mesma métrica.
 *
 * Os nomes antigos foram mantidos (mesmo quando redundantes) para não quebrar
 * nenhuma chamada existente — mas agora apontam todos para a mesma escala.
 */
internal object UiTokens {
    // ---- Grade base -------------------------------------------------------
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp

    // ---- Margens de tela (iguais em TODAS as telas) -----------------------
    val screenHorizontalPadding = space4      // 16
    val screenVerticalPadding = space2        // 8
    val screenPadding = space4                // 16
    val pageHorizontal = space4               // 16
    val pageVertical = space2                 // 8
    val compactPadding = space3               // 12
    val headerPadding = 0.dp
    val standardToolbarPadding = 0.dp

    // ---- Espaçamentos internos -------------------------------------------
    val itemSpacing = space1                  // 4
    val toolbarGap = space2                   // 8
    val sectionSpacing = 10.dp
    val titleSpacing = space2                 // 8
    val listHeaderGap = 6.dp
    val standardListTopSpacing = 6.dp
    val formFieldSpacing = 10.dp
    val songListSpacing = 2.dp
    val songListBottomPadding = 10.dp

    // ---- Barra de rolagem -------------------------------------------------
    val scrollbarWidth = 5.dp
    val scrollbarTouchWidth = 28.dp
    val scrollbarThumbHeight = 112.dp
    val scrollbarReserve = space6             // 24
    val songListEndPadding = space6           // 24 (mesmo valor da reserva)
    val scrollbarInset = space1               // 4
    val jumpEndPadding = space2               // 8
    val jumpBottomPadding = space2            // 8

    // ---- Cartões e linhas -------------------------------------------------
    val cardRadius = space3                   // 12
    val cardPadding = 10.dp
    val cardItemHorizontalPadding = 10.dp
    val songItemRadius = space2               // 8: linhas planas, só o toque e a música atual mostram o formato
    val songItemPadding = 6.dp
    val songItemSpacing = 2.dp
    val songItemArtworkSize = 52.dp
    val songItemHeight = 64.dp
    val compactRowHeight = 64.dp
    val mostPlayedRowHeight = 64.dp
    val rankWidth = 22.dp
    val reorderControlsWidth = 32.dp

    // ---- Controles --------------------------------------------------------
    val controlHeight = 48.dp
    val searchHeight = 48.dp
    val selectionRowHeight = 48.dp
    val toolbarIconButtonSize = 42.dp
    val iconButtonSize = 44.dp
    val smallIconButtonSize = 36.dp
    val primaryIconSize = 24.dp
    val smallIconSize = 20.dp
    val buttonContentPadding = 10.dp
    val buttonRadius = space6                 // 24: botões em pílula
    val fieldRadius = 10.dp                   // campos de texto e busca

    // ---- Barras e player --------------------------------------------------
    val bottomBarHeight = 68.dp
    val bottomBarIconSize = 22.dp
    val bottomBarLabelSize = 12.sp
    val miniPlayerHeight = 110.dp
    val miniPlayerArtworkSize = 56.dp

    // ---- Telas específicas ------------------------------------------------
    val albumCardHeight = 296.dp // não usado: o card agora tem altura natural
    val albumArtworkHeight = 168.dp
    val detailArtworkSize = 164.dp
    val dialogPadding = space4                // 16
    val dialogArtworkSize = 220.dp
    val editorArtworkSize = 180.dp
    val suggestionCardWidth = 140.dp
    val suggestionArtworkHeight = 116.dp
}

internal data class BottomNavSpec(
    val label: String,
    val icon: ImageVector,
)

@Composable
internal fun CompactBottomBar(
    items: List<BottomNavSpec>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    // Mesmo fundo da tela, sem sombra: só o ícone e o nome da aba atual ganham a cor de destaque.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(UiTokens.bottomBarHeight),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "tabTint",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelected(index) }
                        .padding(horizontal = 2.dp, vertical = UiTokens.space2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(UiTokens.bottomBarIconSize + 4.dp),
                        tint = tint,
                    )
                    Spacer(Modifier.height(3.dp))
                    // Em alguns idiomas o nome da aba é longo: a letra diminui até caber, sem cortar a palavra.
                    BasicText(
                        item.label,
                        maxLines = 1,
                        style = LocalTextStyle.current.copy(
                            color = tint,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = UiTokens.bottomBarLabelSize, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun StandardScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = UiTokens.screenHorizontalPadding,
            vertical = UiTokens.screenVerticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}

@Composable
internal fun StandardPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = UiTokens.pageHorizontal,
            vertical = UiTokens.pageVertical,
        ),
        content = content,
    )
}

/** Título de tela — mesma tipografia e mesma altura em todas as abas. */
@Composable
internal fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    // Título longo (nome de álbum, playlist, artista...) rola em loop em vez de ser cortado.
    ScrollingText(title, modifier = modifier, style = MaterialTheme.typography.headlineMedium)
}

/**
 * Cabeçalho padrão de tela: [voltar] título [ações].
 * Mantém sempre a mesma altura, independente de haver botão de voltar.
 */
@Composable
internal fun ScreenHeaderRow(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = UiTokens.iconButtonSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, t(R.string.voltar), modifier = Modifier.size(UiTokens.primaryIconSize))
            }
            Spacer(Modifier.width(UiTokens.space1))
        }
        ScreenTitle(title, Modifier.weight(1f))
        actions()
    }
}

/**
 * Linha de lista usada por playlists, artistas, gêneros e afins: plana, sem borda nem fundo, como as músicas.
 * Antes esse bloco estava duplicado em quatro telas com paddings diferentes;
 * agora todas passam por aqui e ficam idênticas.
 */
@Composable
internal fun ListCardRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = UiTokens.compactRowHeight,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Par de botões de ação de largura igual (Tocar / Aleatório, Criar / Importar, ...). */
@Composable
internal fun ActionButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Botão de ação padrão do app, em pílula: mesma altura e mesmo espaçamento interno em todas as telas.
 * [emphasized] é o botão principal da tela (Tocar), cheio na cor de destaque; os demais são tonais.
 */
@Composable
internal fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: androidx.compose.ui.graphics.Color? = null,
    emphasized: Boolean = false,
) {
    val shape = RoundedCornerShape(UiTokens.buttonRadius)
    val padding = PaddingValues(horizontal = UiTokens.buttonContentPadding, vertical = 0.dp)
    val label: @Composable RowScope.() -> Unit = {
        Icon(icon, null, modifier = Modifier.size(UiTokens.smallIconSize))
        Spacer(Modifier.width(UiTokens.space2))
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
    if (emphasized) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.height(UiTokens.controlHeight), shape = shape, contentPadding = padding, content = label)
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(UiTokens.controlHeight),
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onSurface,
            ),
            content = label,
        )
    }
}

internal fun Modifier.standardListSpace(): Modifier =
    padding(end = UiTokens.scrollbarReserve)

@Composable
internal fun SelectionToolbar(
    selectedCount: Int,
    totalCount: Int,
    onCancel: () -> Unit,
    onToggleAll: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(UiTokens.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(UiTokens.iconButtonSize)) {
            Icon(Icons.Rounded.Close, t(R.string.cancelar_selecao), modifier = Modifier.size(UiTokens.primaryIconSize))
        }
        Text(
            tp(R.plurals.n_selected, selectedCount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleAll) {
            Text(if (totalCount > 0 && selectedCount == totalCount) t(R.string.nenhuma) else t(R.string.todas))
        }
        actions()
    }
}

@Composable
internal fun SelectionToggle(
    active: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(UiTokens.selectionRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            if (active) t(R.string.selecionando) else t(R.string.selecionar),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(UiTokens.space1))
        IconButton(onClick = onToggle, modifier = Modifier.size(UiTokens.smallIconButtonSize)) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = if (active) t(R.string.encerrar_selecao) else t(R.string.selecionar),
                modifier = Modifier.size(UiTokens.primaryIconSize),
            )
        }
    }
}

/**
 * Cores de um botão/filtro selecionado: no tema escuro fica claro (invertido); no claro, um lilás suave com texto
 * escuro (um botão todo preto era pesado demais).
 */
@Composable
internal fun selectedContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface

@Composable
internal fun selectedContentColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.background

internal val secondaryTextColor: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun SectionTitle(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = UiTokens.space2), verticalArrangement = Arrangement.spacedBy(UiTokens.space1)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryTextColor)
        }
    }
}

/**
 * Barra de pesquisa.
 *
 * Antes era um OutlinedTextField com altura fixa: o Material reserva 56dp para
 * esse componente e forçar menos que isso cortava as descidas das letras (g, p,
 * ç). Aqui o campo é montado à mão, então a altura é realmente nossa e o texto
 * fica sempre centralizado e inteiro.
 */
@Composable
internal fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: (@Composable (() -> Unit))? = null,
) {
    val shape = RoundedCornerShape(UiTokens.fieldRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(UiTokens.searchHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = UiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(UiTokens.smallIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(UiTokens.space2))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(UiTokens.space2))
            trailingContent()
        }
    }
}

@Composable
internal fun FormCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Campos soltos na tela, sem cartão em volta.
    Column(
        modifier.fillMaxWidth().padding(vertical = UiTokens.space1),
        verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing),
        content = content,
    )
}

@Composable
internal fun EditableField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(UiTokens.fieldRadius),
        label = { Text(label) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

internal fun Modifier.screenPadding() = padding(UiTokens.screenPadding)
internal fun Modifier.listSpace() = padding(end = UiTokens.scrollbarReserve)
internal fun Modifier.songListSpace() = padding(end = UiTokens.songListEndPadding)

internal fun Modifier.standardSongList(): Modifier = padding(end = UiTokens.scrollbarReserve)
