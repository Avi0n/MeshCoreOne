package one.meshcore.shared.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Platform dynamic Material color scheme (Android 12+ wallpaper colors).
 * Null when unavailable (desktop, pre-API 31, or branded non-default theme path).
 */
@Composable
expect fun platformDynamicColorScheme(useDark: Boolean): ColorScheme?

/** True when Default theme follows wallpaper colors (Android 12+). */
expect fun platformDefaultThemeUsesWallpaperColors(): Boolean
