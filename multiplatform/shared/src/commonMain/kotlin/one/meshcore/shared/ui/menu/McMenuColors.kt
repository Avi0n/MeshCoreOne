package one.meshcore.shared.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Label / icon colors for [McMenuRole]. Pure mapping so unit tests can assert without Compose. */
object McMenuColors {
    fun labelColor(role: McMenuRole, onSurface: Color, error: Color): Color = when (role) {
        McMenuRole.Default, McMenuRole.Selected -> onSurface
        McMenuRole.Destructive -> error
    }

    fun iconColor(role: McMenuRole, onSurfaceVariant: Color, error: Color): Color = when (role) {
        McMenuRole.Default, McMenuRole.Selected -> onSurfaceVariant
        McMenuRole.Destructive -> error
    }
}

@Composable
internal fun mcMenuLabelColor(role: McMenuRole): Color = McMenuColors.labelColor(
    role = role,
    onSurface = MaterialTheme.colorScheme.onSurface,
    error = MaterialTheme.colorScheme.error,
)

@Composable
internal fun mcMenuIconColor(role: McMenuRole): Color = McMenuColors.iconColor(
    role = role,
    onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
    error = MaterialTheme.colorScheme.error,
)

internal fun Color.mcMenuEnabledColor(enabled: Boolean): Color =
    if (enabled) this else copy(alpha = MC_MENU_DISABLED_ALPHA)

/** Material disabled-content alpha for menu and action-sheet rows. */
internal const val MC_MENU_DISABLED_ALPHA = 0.38f
