package cn.lemwood.keyvault.ui.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DesktopButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 36.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        colors = when {
            primary -> ButtonDefaults.buttonColorsPrimary()
            danger -> ButtonDefaults.buttonColors(contentColor = colors.error)
            else -> ButtonDefaults.buttonColors()
        },
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    selected: Boolean? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    TooltipArea(
        tooltip = {
            Surface(color = colors.surfaceContainerHigh, contentColor = colors.onSurfaceContainerHigh) {
                Text(description, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MiuixTheme.textStyles.footnote2)
            }
        },
        delayMillis = 420,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            minWidth = 34.dp,
            minHeight = 34.dp,
            backgroundColor = if (selected == true) colors.primaryContainer else Color.Transparent,
            modifier = if (selected == null) Modifier else Modifier.semantics { this.selected = selected },
        ) {
            Icon(
                icon, description, Modifier.size(18.dp),
                tint = when {
                    !enabled -> colors.disabledOnSurface
                    danger -> colors.error
                    selected == true -> colors.onPrimaryContainer
                    else -> colors.onSurface
                },
            )
        }
    }
}

@Composable
internal fun DesktopEmpty(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier.padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = colors.primary)
        Text(title, color = colors.onSurface, style = MiuixTheme.textStyles.title4, textAlign = TextAlign.Center)
        Text(detail, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
internal fun DesktopDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    WindowDialog(show = true, title = title, modifier = Modifier.widthIn(max = 460.dp), onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}
