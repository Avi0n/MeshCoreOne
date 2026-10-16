package one.meshcore.shared.chat.translation

import one.meshcore.shared.chat.text.MentionUtilities

/**
 * Synchronous language-detection gates that match iOS `MessageLanguageDetector`. A recognizer
 * hypothesis is only trusted after the letter-count and confidence floors; otherwise the result is
 * undetermined and Translation is not offered.
 */
object MessageLanguageDetector {
    const val MINIMUM_LETTER_COUNT = 12
    const val MINIMUM_CONFIDENCE = 0.80
    const val MINIMUM_SHORT_LETTER_COUNT = 8
    const val MINIMUM_SHORT_CONFIDENCE = 0.50
    const val UNDETERMINED_TAG = "und"

    private val mentionRegex = Regex(MentionUtilities.MENTION_PATTERN)

    /**
     * `@[name]` tokens inflate letter count and lower recognizer confidence; floor and identify use
     * the remainder, matching iOS `detectionSample(from:)`.
     */
    fun detectionSample(text: String): String = text.replace(mentionRegex, " ").trim()

    fun dominantLanguage(text: String, hypothesis: Pair<String, Double>?): DetectedLanguage {
        val sample = detectionSample(text)
        val letterCount = letterCount(sample)
        val eligible = letterCount >= MINIMUM_LETTER_COUNT ||
            (letterCount >= MINIMUM_SHORT_LETTER_COUNT && sample.any { it.isWhitespace() })
        val language = hypothesis?.first?.let(::collapsedLanguageCode)
            ?.takeIf { it.isNotEmpty() && it != UNDETERMINED_TAG }
        val confidence = hypothesis?.second ?: 0.0
        val confident = confidence >= MINIMUM_CONFIDENCE ||
            (letterCount < MINIMUM_LETTER_COUNT && confidence >= MINIMUM_SHORT_CONFIDENCE)
        return if (eligible && language != null && confident) {
            DetectedLanguage.Identified(language)
        } else {
            DetectedLanguage.Undetermined
        }
    }

    fun letterCount(text: String): Int = text.count { it.isLetter() }

    fun isSameLanguage(lhs: String, rhs: String): Boolean = collapsedLanguageCode(lhs) == collapsedLanguageCode(rhs)

    fun collapsedLanguageCode(identifier: String): String {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return trimmed
        val sep = trimmed.indexOfFirst { it == '-' || it == '_' }
        val head = if (sep > 0) trimmed.substring(0, sep) else trimmed
        return head.lowercase()
    }
}

sealed class DetectedLanguage {
    data object Undetermined : DetectedLanguage()
    data class Identified(val languageCode: String) : DetectedLanguage()

    val code: String?
        get() = (this as? Identified)?.languageCode
}
