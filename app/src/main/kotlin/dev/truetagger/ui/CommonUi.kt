package dev.truetagger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector

internal object UiTokens {
    // Geometria única do app. A ordem e a posição relativa dos componentes são mantidas;
    // apenas margens, tamanhos e espaçamentos são normalizados.
    val screenPadding = 18.dp
    val compactPadding = 10.dp
    val searchHeight = 52.dp
    val scrollbarWidth = 5.dp
    val scrollbarTouchWidth = 28.dp
    val scrollbarThumbHeight = 112.dp
    val scrollbarReserve = 28.dp
    val screenHorizontalPadding = 18.dp
    val screenVerticalPadding = 10.dp
    val pageHorizontal = 18.dp
    val pageVertical = 10.dp
    val standardToolbarPadding = 0.dp
    val standardListTopSpacing = 6.dp
    val selectionRowHeight = 44.dp
    val listHeaderGap = 6.dp
    val songListEndPadding = 28.dp
    val songListSpacing = 5.dp
    val songListBottomPadding = 8.dp
    val jumpEndPadding = 6.dp
    val jumpBottomPadding = 6.dp
    val scrollbarInset = 1.dp
    val headerPadding = 0.dp
    val toolbarGap = 8.dp
    val titleSpacing = 6.dp
    val sectionSpacing = 8.dp
    val itemSpacing = 3.dp
    val cardRadius = 12.dp
    val cardPadding = 10.dp
    val cardItemHorizontalPadding = 8.dp
    val reorderControlsWidth = 40.dp
    val formFieldSpacing = 8.dp
    val songItemRadius = 10.dp
    val songItemPadding = 5.dp
    val songItemArtworkSize = 48.dp
    val songItemSpacing = 4.dp
    val compactRowHeight = 64.dp
    val albumCardHeight = 300.dp
    val albumArtworkHeight = 152.dp
    val bottomBarHeight = 64.dp
    // Botões e controles interativos mantêm sua geometria original.
    val controlHeight = 48.dp
    val toolbarIconButtonSize = 44.dp
    val primaryIconSize = 22.dp
    val smallIconSize = 20.dp
    val mostPlayedRowHeight = 80.dp
    val songItemHeight = 64.dp
    val detailArtworkSize = 200.dp
    val miniPlayerHeight = 88.dp
    val miniPlayerArtworkSize = 48.dp
    val rankWidth = 24.dp
    val dialogPadding = 16.dp
    val dialogArtworkSize = 240.dp
    val editorArtworkSize = 210.dp
    val suggestionCardWidth = 148.dp
    val suggestionArtworkHeight = 124.dp
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
    Surface(
        modifier = Modifier.fillMaxWidth().height(UiTokens.bottomBarHeight),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelected(index) }
                        .padding(horizontal = 1.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(18.dp),
                        tint = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        item.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 9.sp,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancelar seleção") }
        Text(
            "$selectedCount selecionada(s)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleAll) {
            Text(if (totalCount > 0 && selectedCount == totalCount) "Nenhuma" else "Todas")
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
            if (active) "Selecionando" else "Selecionar",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (active) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = if (active) "Encerrar seleção" else "Selecionar",
            )
        }
    }
}

internal val secondaryTextColor: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun SectionTitle(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = secondaryTextColor)
        }
    }
}

@Composable
internal fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: (@Composable (() -> Unit))? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(UiTokens.searchHeight),
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = trailingContent,
    )
}

@Composable
internal fun FormCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UiTokens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(UiTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing),
            content = content,
        )
    }
}

@Composable
internal fun EditableField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
    )
}

internal fun Modifier.screenPadding() = padding(UiTokens.screenPadding)
internal fun Modifier.listSpace() = padding(end = UiTokens.scrollbarReserve)
internal fun Modifier.songListSpace() = padding(end = UiTokens.songListEndPadding)

internal fun Modifier.standardSongList(): Modifier = padding(end = UiTokens.scrollbarReserve)
