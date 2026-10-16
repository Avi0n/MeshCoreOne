package one.meshcore.shared.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun platformDynamicColorScheme(useDark: Boolean): ColorScheme? = null

actual fun platformDefaultThemeUsesWallpaperColors(): Boolean = false
