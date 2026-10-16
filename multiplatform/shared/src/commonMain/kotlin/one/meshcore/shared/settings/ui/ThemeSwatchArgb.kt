package one.meshcore.shared.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.meshcore.shared.theme.AppTheme

internal data class ThemeSwatchArgb(val primary: Long, val secondary: Long?, val canvas: Long?)

/** Light roles when present, otherwise dark. A null role means the swatch falls back at draw time. */
internal fun AppTheme.themeSwatchArgb(): ThemeSwatchArgb = ThemeSwatchArgb(
    primary = accentArgb,
    secondary = light?.secondary ?: dark?.secondary,
    canvas = light?.background ?: dark?.background,
)

/** Circle of canvas with the theme's primary and secondary dots. Callers own diameter, dots, and border. */
@Composable
internal fun ThemeColorSwatch(
    theme: AppTheme,
    diameter: Dp,
    dotSize: Dp,
    borderWidth: Dp,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    val roles = theme.themeSwatchArgb()
    val primary = Color(roles.primary)
    val secondary = roles.secondary?.let(::Color)
        ?: primary.copy(alpha = THEME_SWATCH_SECONDARY_FALLBACK_ALPHA)
    val canvas = roles.canvas?.let(::Color) ?: MaterialTheme.colorScheme.surface
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(canvas)
            .border(borderWidth, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(THEME_SWATCH_DOT_SPACING)) {
            Box(Modifier.size(dotSize).clip(CircleShape).background(primary))
            Box(Modifier.size(dotSize).clip(CircleShape).background(secondary))
        }
    }
}

private const val THEME_SWATCH_SECONDARY_FALLBACK_ALPHA = 0.65f

private val THEME_SWATCH_DOT_SPACING = 2.dp
