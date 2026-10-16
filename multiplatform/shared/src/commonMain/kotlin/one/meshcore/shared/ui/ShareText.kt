package one.meshcore.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Shares already-decoded image bytes (PNG/JPEG). Default is a no-op. */
val LocalShareImageBytes = staticCompositionLocalOf<(ByteArray) -> Unit> { {} }
