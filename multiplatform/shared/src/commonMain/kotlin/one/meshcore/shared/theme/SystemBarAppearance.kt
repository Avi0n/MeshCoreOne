package one.meshcore.shared.theme

import androidx.compose.runtime.Composable

/**
 * Drives the platform system-bar (status/navigation) icon appearance from the resolved in-app color
 * scheme, so a Light in-app theme keeps dark status-bar icons even when the system theme is dark.
 * A no-op on hosts without system bars.
 */
@Composable
expect fun SystemBarAppearance(useDark: Boolean)
