package one.meshcore.shared.chat.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageTranslationChromeTest {
    @Test
    fun detectedGermanAgainstEnglishIsAnOffer() {
        val chrome = MessageTranslationChrome.resolved(
            detected = DetectedLanguage.Identified("de"),
            phase = null,
            preferredLanguageCode = "en",
        )
        assertEquals(MessageTranslationPhase.Offer, chrome?.phase)
        assertEquals("de", chrome?.sourceLanguageCode)
    }

    @Test
    fun sameLanguageAsPreferredIsNone() {
        assertNull(
            MessageTranslationChrome.resolved(
                detected = DetectedLanguage.Identified("de"),
                phase = null,
                preferredLanguageCode = "de",
            ),
        )
        assertNull(
            MessageTranslationChrome.resolved(
                detected = DetectedLanguage.Identified("de"),
                phase = null,
                preferredLanguageCode = "de-DE",
            ),
        )
        assertNull(
            MessageTranslationChrome.resolved(
                detected = DetectedLanguage.Identified("zh"),
                phase = null,
                preferredLanguageCode = "zh-Hans",
            ),
        )
    }

    @Test
    fun presentNilDetectionIsNone() {
        assertNull(
            MessageTranslationChrome.resolved(
                detected = null,
                phase = null,
                preferredLanguageCode = "en",
            ),
        )
        assertNull(
            MessageTranslationChrome.resolved(
                detected = null,
                phase = MessageTranslationPhase.Offer,
                preferredLanguageCode = "en",
            ),
        )
        assertNull(
            MessageTranslationChrome.resolved(
                detected = DetectedLanguage.Undetermined,
                phase = MessageTranslationPhase.Offer,
                preferredLanguageCode = "en",
            ),
        )
    }

    @Test
    fun inProgressIsKept() {
        val chrome = MessageTranslationChrome.resolved(
            detected = DetectedLanguage.Identified("de"),
            phase = MessageTranslationPhase.InProgress,
            preferredLanguageCode = "en",
        )
        assertEquals(MessageTranslationPhase.InProgress, chrome?.phase)
    }

    @Test
    fun showingIsKeptWhenTargetStillMatchesPreferred() {
        val showing = MessageTranslationPhase.Showing("Hello", "en")
        val chrome = MessageTranslationChrome.resolved(
            detected = DetectedLanguage.Identified("de"),
            phase = showing,
            preferredLanguageCode = "en",
        )
        assertEquals(showing, chrome?.phase)
    }

    @Test
    fun showingForADifferentTargetFallsBackToOffer() {
        val chrome = MessageTranslationChrome.resolved(
            detected = DetectedLanguage.Identified("de"),
            phase = MessageTranslationPhase.Showing("Hello", "en"),
            preferredLanguageCode = "fr",
        )
        assertEquals(MessageTranslationPhase.Offer, chrome?.phase)
    }

    @Test
    fun showingForADifferentTargetThatMatchesTheSourceIsNone() {
        assertNull(
            MessageTranslationChrome.resolved(
                detected = DetectedLanguage.Identified("de"),
                phase = MessageTranslationPhase.Showing("Hello", "en"),
                preferredLanguageCode = "de",
            ),
        )
    }
}
