package one.meshcore.shared.ui

import androidx.compose.runtime.Composable

/**
 * Whether the platform's "reduce/remove animations" accessibility setting is on. The status pill drops
 * its slide/spring animations to instant transitions when this is true, mirroring the iOS Reduce Motion
 * handling. Android reads `Settings.Global.ANIMATOR_DURATION_SCALE`; the desktop build has no such
 * system setting and reports `false`.
 */
@Composable
expect fun rememberReduceMotion(): Boolean
