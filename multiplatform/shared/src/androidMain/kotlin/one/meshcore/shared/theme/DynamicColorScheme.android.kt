package one.meshcore.shared.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun platformDynamicColorScheme(useDark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual fun platformDefaultThemeUsesWallpaperColors(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
