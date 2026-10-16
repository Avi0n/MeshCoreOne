package one.meshcore.shared.map.ui

import androidx.compose.runtime.staticCompositionLocalOf
import one.meshcore.shared.theme.AppColorSchemePreference

val LocalMapColorSchemePreference = staticCompositionLocalOf { AppColorSchemePreference.SYSTEM }
val LocalMapOffline = staticCompositionLocalOf { false }
