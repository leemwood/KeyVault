package cn.lemwood.keyvault.ui.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal class DesktopColors(dark: Boolean) {
    val sidebar = if (dark) Color(0xFF151C2A) else Color(0xFFEAF0F8)
    val canvas = if (dark) Color(0xFF191F2B) else Color(0xFFF6F8FC)
    val surface = if (dark) Color(0xFF202838) else Color.White
    val field = if (dark) Color(0xFF151C28) else Color(0xFFF1F5FB)
    val text = if (dark) Color(0xFFEDF2FC) else Color(0xFF18243A)
    val muted = if (dark) Color(0xFFABB8CD) else Color(0xFF515F76)
    val line = if (dark) Color(0xFF344155) else Color(0xFFDCE4F0)
    val accent = if (dark) Color(0xFF9BB9FF) else Color(0xFF204FC1)
    val onAccent = if (dark) Color(0xFF102148) else Color.White
    val selected = if (dark) Color(0xFF293D62) else Color(0xFFDFE9FD)
    val hover = if (dark) Color(0xFF2A3448) else Color(0xFFE5ECF7)
    val danger = if (dark) Color(0xFFFFB1B4) else Color(0xFFA52231)
    val active = if (dark) Color(0xFF77DDB5) else Color(0xFF17744F)

    val miuix = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = selected,
        onPrimaryContainer = text,
        error = danger,
        background = canvas,
        onBackground = text,
        onBackgroundVariant = muted,
        surface = canvas,
        onSurface = text,
        surfaceVariant = field,
        onSurfaceSecondary = muted,
        onSurfaceVariantSummary = muted,
        onSurfaceVariantActions = muted,
        surfaceContainer = surface,
        onSurfaceContainer = text,
        onSurfaceContainerVariant = muted,
        outline = line,
        dividerLine = line,
    )
}

internal val LocalDesktopColors = staticCompositionLocalOf<DesktopColors> { error("Desktop theme is missing") }

@Composable
internal fun DesktopButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = LocalDesktopColors.current
    val tint = when {
        !enabled -> colors.muted
        primary -> colors.onAccent
        danger -> colors.danger
        else -> colors.text
    }
    Row(
        modifier.desktopAction(
            onClick = onClick,
            background = if (primary && enabled) colors.accent else colors.surface,
            outlined = !primary || !enabled,
            enabled = enabled,
        ).height(34.dp).padding(horizontal = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(16.dp), tint = tint) }
        Text(text, color = tint, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalDesktopColors.current
    TooltipArea(
        modifier = modifier,
        tooltip = {
            Text(
                description,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.text)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                color = colors.surface,
                style = MiuixTheme.textStyles.footnote2,
            )
        },
        delayMillis = 420,
    ) {
        Box(
            Modifier.desktopAction(onClick, selected = selected, enabled = enabled).size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, description, Modifier.size(18.dp),
                tint = when {
                    !enabled -> colors.muted
                    danger -> colors.danger
                    selected == true -> colors.accent
                    else -> colors.text
                },
            )
        }
    }
}

@Composable
internal fun Modifier.desktopAction(
    onClick: () -> Unit,
    selected: Boolean? = null,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    outlined: Boolean = false,
): Modifier {
    val colors = LocalDesktopColors.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val fill = when {
        selected == true -> colors.selected
        hovered && enabled && background == colors.accent -> colors.accent.copy(alpha = 0.86f)
        hovered && enabled -> colors.hover
        else -> background
    }
    val border = when {
        focused && background == colors.accent -> colors.text
        focused -> colors.accent
        outlined -> colors.line
        else -> Color.Transparent
    }
    return clip(shape).background(fill).border(1.dp, border, shape).then(
        if (selected == null) {
            Modifier.clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        } else {
            Modifier.selectable(selected, interactions, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
        },
    )
}

@Composable
internal fun DesktopEmpty(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val colors = LocalDesktopColors.current
    Column(
        modifier.padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(colors.selected),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = colors.accent)
        }
        Text(title, color = colors.text, style = MiuixTheme.textStyles.title4, textAlign = TextAlign.Center)
        Text(detail, color = colors.muted, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
internal fun DesktopDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalDesktopColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.width(440.dp).clip(shape).background(colors.surface)
                .border(1.dp, colors.line, shape).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = colors.text, style = MiuixTheme.textStyles.title3, modifier = Modifier.fillMaxWidth())
            content()
        }
    }
}
