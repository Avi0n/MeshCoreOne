package one.meshcore.shared.ui

import androidx.compose.runtime.Composable

/** Desktop has no single high-contrast system API mapped yet; default off. */
@Composable
actual fun rememberHighContrast(): Boolean = false
