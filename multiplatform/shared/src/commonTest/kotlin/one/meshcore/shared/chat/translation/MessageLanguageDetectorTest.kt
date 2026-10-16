package one.meshcore.shared.chat.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageLanguageDetectorTest {
    @Test
    fun emptyOrShortTextIsUndetermined() {
        assertEquals(DetectedLanguage.Undetermined, MessageLanguageDetector.dominantLanguage("", "en" to 1.0))
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage("hi there", "es" to 0.99),
        )
    }

    @Test
    fun highConfidenceForeignTextIsIdentified() {
        val text = "Bonjour tout le monde ici"
        assertTrue(MessageLanguageDetector.letterCount(text) >= MessageLanguageDetector.MINIMUM_LETTER_COUNT)
        assertEquals(
            DetectedLanguage.Identified("fr"),
            MessageLanguageDetector.dominantLanguage(text, "fr" to 0.92),
        )
    }

    @Test
    fun lowConfidenceIsUndetermined() {
        val text = "Bonjour tout le monde ici"
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage(text, "fr" to 0.5),
        )
    }

    @Test
    fun collapsedLanguageCodeDropsRegion() {
        assertEquals("en", MessageLanguageDetector.collapsedLanguageCode("en-US"))
        assertEquals("zh", MessageLanguageDetector.collapsedLanguageCode("zh_Hans"))
        assertEquals("fr", MessageLanguageDetector.collapsedLanguageCode("FR"))
        assertTrue(MessageLanguageDetector.isSameLanguage("en-US", "en-GB"))
        assertFalse(MessageLanguageDetector.isSameLanguage("en", "es"))
    }

    @Test
    fun undeterminedTagIsRejected() {
        val text = "Bonjour tout le monde ici"
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage(text, "und" to 1.0),
        )
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage(text, "und-US" to 1.0),
        )
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage(text, null),
        )
    }

    @Test
    fun shortTextNeedsAWordBreakEvenAtFullConfidence() {
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage("abcdefgh", "en" to 1.0),
        )
    }

    @Test
    fun detectionSampleStripsMentionTokens() {
        assertEquals(
            ": no problems!",
            MessageLanguageDetector.detectionSample("@[1wNodeTest]: no problems!"),
        )
        assertEquals("ok", MessageLanguageDetector.detectionSample("@[1wNodeTest] ok"))
    }

    @Test
    fun mentionPrefixedShortEnglishIsIdentified() {
        assertEquals(
            DetectedLanguage.Identified("en"),
            MessageLanguageDetector.dominantLanguage("@[1wNodeTest]: no problems!", "en" to 0.55),
        )
    }

    @Test
    fun mentionPlusShortRemainderIsUndetermined() {
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage("@[1wNodeTest] ok", "en" to 0.99),
        )
    }

    @Test
    fun majorityConfidenceOnLongTextIsIdentified() {
        val text = "Bonjour tout le monde ici"
        assertEquals(
            DetectedLanguage.Identified("fr"),
            MessageLanguageDetector.dominantLanguage(text, "fr" to 0.80),
        )
    }

    @Test
    fun lowConfidenceShortRemainderIsUndetermined() {
        assertEquals(
            DetectedLanguage.Undetermined,
            MessageLanguageDetector.dominantLanguage("en route now", "en" to 0.49),
        )
    }
}
