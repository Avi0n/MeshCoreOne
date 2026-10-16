package one.meshcore.shared.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * Active [AppTheme] for the composition tree. Used by avatars and themed surfaces so identity
 * gamut colors track the selected Appearance theme.
 */
val LocalAppTheme = compositionLocalOf { ThemeRegistry.byId(AppTheme.DEFAULT_ID) }

/** Whether the resolved appearance is dark (after forceDark and scheme preference). */
val LocalUseDarkTheme = compositionLocalOf { false }
