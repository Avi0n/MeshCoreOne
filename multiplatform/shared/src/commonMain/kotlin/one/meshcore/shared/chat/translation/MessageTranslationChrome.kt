package one.meshcore.shared.chat.translation

/** In-bubble Translation offer chrome. Null means no row; stored message text is never replaced. */
data class MessageTranslationChrome(val phase: MessageTranslationPhase, val sourceLanguageCode: String) {
    companion object {
        /**
         * In-flight wins; showing wins only if its target still matches preferred.
         * Otherwise an offer exists only when detection differs from preferred.
         */
        fun resolved(
            detected: DetectedLanguage?,
            phase: MessageTranslationPhase?,
            preferredLanguageCode: String,
        ): MessageTranslationChrome? {
            val sourceLanguageCode = detected?.code ?: return null
            val visiblePhase = when {
                phase == MessageTranslationPhase.InProgress -> phase
                phase is MessageTranslationPhase.Showing &&
                    MessageLanguageDetector.isSameLanguage(phase.targetLanguageCode, preferredLanguageCode) -> phase
                !MessageLanguageDetector.isSameLanguage(sourceLanguageCode, preferredLanguageCode) ->
                    MessageTranslationPhase.Offer
                else -> null
            }
            return visiblePhase?.let { MessageTranslationChrome(it, sourceLanguageCode) }
        }
    }
}

sealed class MessageTranslationPhase {
    data object Offer : MessageTranslationPhase()
    data object InProgress : MessageTranslationPhase()
    data class Showing(val translatedText: String, val targetLanguageCode: String) : MessageTranslationPhase()
}
