package one.meshcore.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.map.SnrQuality

/** Favorite star was iOS system yellow; tertiary tracks the theme accent. */
internal val FAVORITE_STAR_ROLE = SignalChromeRole.TERTIARY

internal fun SnrQuality.signalChromeRole(): SignalChromeRole = when (this) {
    SnrQuality.EXCELLENT, SnrQuality.GOOD -> SignalChromeRole.PRIMARY
    SnrQuality.FAIR -> SignalChromeRole.TERTIARY
    SnrQuality.POOR -> SignalChromeRole.ERROR
    SnrQuality.UNKNOWN -> SignalChromeRole.MUTED
}

@Composable
internal fun SignalChromeRole.tint(muted: Color = MaterialTheme.colorScheme.onSurfaceVariant): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SignalChromeRole.PRIMARY -> scheme.primary
        SignalChromeRole.SECONDARY -> scheme.secondary
        SignalChromeRole.TERTIARY -> scheme.tertiary
        SignalChromeRole.ERROR -> scheme.error
        SignalChromeRole.MUTED -> muted
    }
}

@Composable
internal fun SignalChromeRole.onTint(): Color {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        SignalChromeRole.PRIMARY -> scheme.onPrimary
        SignalChromeRole.SECONDARY -> scheme.onSecondary
        SignalChromeRole.TERTIARY -> scheme.onTertiary
        SignalChromeRole.ERROR -> scheme.onError
        SignalChromeRole.MUTED -> scheme.onSurface
    }
}

/** Theme SNR tint. Unknown uses onSurfaceVariant unless the caller passes another muted color. */
@Composable
internal fun SnrQuality.barTint(unknown: Color = MaterialTheme.colorScheme.onSurfaceVariant): Color =
    signalChromeRole().tint(muted = unknown)
