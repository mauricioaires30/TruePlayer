package dev.truetagger.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.truetagger.android.t
import dev.truetagger.android.R

/*
 * Peças de tela compartilhadas por Configurações, Git e diálogos: um grupo (título + cartão), linhas com
 * ícone, título e uma frase curta, e um diálogo padrão. Mudar aqui muda o app inteiro do mesmo jeito.
 */

/** Grupo de Configurações: título em negrito e as linhas direto na tela, sem cartão. */
@Composable
internal fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = UiTokens.space4, bottom = UiTokens.space1)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = UiTokens.space1),
        )
        Column(Modifier.fillMaxWidth().animateContentSize(), content = content)
    }
}

/** As linhas planas não precisam de divisória: fica só o espaço delas. */
@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.height(0.dp))
}

/** Ícone simples, sem fundo, à esquerda de cada linha. */
@Composable
internal fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
}

/**
 * Linha de Configurações: ícone, título e uma frase curta. Com [onClick] a linha inteira é tocável e mostra
 * uma seta (a menos que [trailing] mostre outra coisa). [destructive] pinta de vermelho ações que apagam algo.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = UiTokens.space1, vertical = UiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon, tint)
        Spacer(Modifier.width(UiTokens.space4))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> {
                Spacer(Modifier.width(UiTokens.space2))
                trailing()
            }
            onClick != null -> Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = UiTokens.space2),
            )
        }
    }
}

/** Linha com chave liga/desliga; tocar em qualquer lugar da linha alterna. */
@Composable
internal fun SettingsSwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** Texto curto à direita de uma linha (ex.: "Trocar", "OK"). */
@Composable
internal fun RowHint(text: String, highlighted: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/**
 * Diálogo padrão do app: tema escuro, conteúdo rolável, botão de confirmar e de cancelar.
 * Sem [confirmText] mostra só um botão para fechar.
 */
@Composable
internal fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String = if (confirmText == null) "OK" else t(R.string.cancelar),
    destructive: Boolean = false,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    TruePlayerTheme {
        AlertDialog(
            onDismissRequest = { if (dismissOnOutside) onDismiss() },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(UiTokens.space2), content = content)
            },
            confirmButton = {
                if (confirmText != null && onConfirm != null) {
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(confirmText, color = if (destructive && confirmEnabled) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text(dismissText) }
                }
            },
            dismissButton = if (confirmText != null && onConfirm != null) {
                { TextButton(onClick = onDismiss) { Text(dismissText) } }
            } else {
                null
            },
        )
    }
}

/** Texto secundário dentro de diálogos (explicações curtas). */
@Composable
internal fun DialogNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
