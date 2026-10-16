package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Semantic haptic events, the Kotlin analogue of the iOS `sensoryFeedback` touch points. Compose
 * Multiplatform's [HapticFeedback] is available in commonMain (Android vibrates, desktop no-ops), so this
 * wraps it in named events rather than spreading raw [HapticFeedbackType] constants across call sites —
 * one place decides which OS feedback type stands in for each iOS sensory kind.
 */
class AppHaptics(private val delegate: HapticFeedback) {
    /** A completed positive action: a message delivered, a connection established, a contact added. */
    fun success() = delegate.performHapticFeedback(HapticFeedbackType.Confirm)

    /** A failed action: a connection attempt or send that didn't go through. */
    fun failure() = delegate.performHapticFeedback(HapticFeedbackType.Reject)

    /** iOS `.warning`: a caution that is not a hard failure (empty scan, destructive sheet, rejected tap). */
    fun warning() = delegate.performHapticFeedback(HapticFeedbackType.ContextClick)

    /** iOS `.selection`: a picker/toggle tick (appearance, language, routing confirm, log expand). */
    fun selection() = delegate.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** iOS `.start`: an action that has begun (composer send). */
    fun start() = delegate.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** A direct-manipulation impact: a long-press opening a menu, a reaction picked, a hop drag. */
    fun tap() = delegate.performHapticFeedback(HapticFeedbackType.LongPress)
}

/** A no-op instance for previews and tests that don't run inside a Compose hierarchy. */
val LocalAppHaptics = staticCompositionLocalOf<AppHaptics?> { null }

/** The current [AppHaptics], backed by [LocalHapticFeedback] unless a test/preview has overridden it. */
@Composable
fun rememberAppHaptics(): AppHaptics {
    LocalAppHaptics.current?.let { return it }
    val delegate = LocalHapticFeedback.current
    return AppHaptics(delegate)
}
