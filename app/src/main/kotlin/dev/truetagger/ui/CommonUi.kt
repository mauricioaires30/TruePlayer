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
    val screenPadding = 14.dp
    val compactPadding = 10.dp
    val searchHeight = 56.dp
    // Geometry shared by every list-based screen. Do not tune per-screen.
    val scrollbarWidth = 9.dp
    val scrollbarTouchWidth = 38.dp
    val scrollbarThumbHeight = 180.dp
    val scrollbarReserve = 38.dp
    val screenHorizontalPadding = 12.dp
    val screenVerticalPadding = 8.dp
    val pageHorizontal = screenHorizontalPadding
    val pageVertical = screenVerticalPadding
    val standardToolbarPadding = 0.dp
    val standardListTopSpacing = 6.dp
    val selectionRowHeight = 42.dp
    val listHeaderGap = 6.dp
    val songListEndPadding = scrollbarReserve
    val songListSpacing = 4.dp
    val songListBottomPadding = 16.dp
    val jumpEndPadding = 8.dp
    val jumpBottomPadding = 8.dp
    val scrollbarInset = 2.dp
    val headerPadding = 4.dp
    val toolbarGap = 8.dp
    val titleSpacing = 6.dp
    val sectionSpacing = 8.dp
    val itemSpacing = 4.dp
    val cardRadius = 12.dp
    val cardPadding = 14.dp
    val cardItemHorizontalPadding = 10.dp
    val reorderControlsWidth = 44.dp
    val formFieldSpacing = 10.dp
    val songItemRadius = 8.dp
    val songItemPadding = 4.dp
    val songItemArtworkSize = 50.dp
    val songItemSpacing = 2.dp
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
        modifier = Modifier.fillMaxWidth().height(68.dp),
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
                        .padding(horizontal = 1.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(19.dp),
                        tint = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        item.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 8.sp,
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        placeholder = { Text(placeholder) },
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
        shape = RoundedCornerShape(20.dp),
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
