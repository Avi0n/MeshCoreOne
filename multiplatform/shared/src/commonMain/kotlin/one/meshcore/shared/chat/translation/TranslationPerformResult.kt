package one.meshcore.shared.chat.translation

/**
 * Outcome of an in-bubble Translate tap. Overlay is the system Translation fallback when the
 * on-device session throws; cancel stays [Finished] with no UI.
 */
sealed interface TranslationPerformResult {
    data object Finished : TranslationPerformResult
    data class PresentSystemOverlay(val text: String) : TranslationPerformResult
}

fun TranslationPerformResult.presentOverlayIfNeeded(present: (String) -> Unit) {
    if (this is TranslationPerformResult.PresentSystemOverlay) present(text)
}
