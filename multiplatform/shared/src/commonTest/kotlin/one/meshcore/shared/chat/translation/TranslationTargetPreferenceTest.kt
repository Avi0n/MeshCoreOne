package one.meshcore.shared.chat.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationTargetPreferenceTest {
    @Test
    fun appSentinelResolvesToTheAppLocaleLanguage() {
        val preference = TranslationTargetPreference.matchAppLanguage
        assertEquals("app", preference.rawValue)
        assertEquals("uk", preference.resolvedLanguageCode("uk-UA"))
    }

    @Test
    fun missingAndEmptyRawValuesMatchTheAppLocale() {
        assertEquals("de", TranslationTargetPreference("").resolvedLanguageCode("de-DE"))
    }

    @Test
    fun danishOverrideIgnoresAppLocale() {
        assertEquals("da", TranslationTargetPreference("da").resolvedLanguageCode("en-US"))
    }

    @Test
    fun regionalOverrideCollapsesToTheLanguageSubtag() {
        assertEquals("en", TranslationTargetPreference("en-GB").resolvedLanguageCode("uk-UA"))
    }

    @Test
    fun suggestedLanguagesSkipAppLocaleUnknownCodesAndLanguagesOutsideTheCatalog() {
        val suggested = TranslationTargetPreference.suggestedLanguageCodes(
            preferredLanguages = listOf("da-DK", "en-US", "xx-YY", "fr-FR"),
            appLocaleIdentifier = "en-US",
            catalog = listOf("da", "de", "en"),
        )
        assertEquals(listOf("da"), suggested)
    }

    @Test
    fun uniqueCompactCodesCollapseRegionsAndDropDuplicates() {
        assertEquals(
            listOf("de", "en", "fr"),
            TranslationTargetPreference.uniqueCompactCodes(from = listOf("de-DE", "de", "en-US", "fr")),
        )
    }

    @Test
    fun systemOverlayFollowsTheAppLocaleForOfferGating() {
        val preference = TranslationTargetPreference.systemOverlay
        assertEquals("overlay", preference.rawValue)
        assertTrue(preference.isSystemOverlay)
        assertFalse(preference.isMatchAppLanguage)
        assertEquals("en", preference.resolvedLanguageCode("en-US"))
    }

    @Test
    fun usesSystemOverlayIsTheBoolOrTheOverlayLanguageSentinel() {
        assertTrue(
            TranslationTargetPreference.usesSystemOverlay(
                languageRawValue = "da",
                useDefaultTranslationApp = true,
            ),
        )
        assertFalse(
            TranslationTargetPreference.usesSystemOverlay(
                languageRawValue = "da",
                useDefaultTranslationApp = false,
            ),
        )
        assertTrue(
            TranslationTargetPreference.usesSystemOverlay(
                languageRawValue = "overlay",
                useDefaultTranslationApp = false,
            ),
        )
    }

    @Test
    fun danishDisplayNameUsesAutonymWithLocalizedSubtitle() {
        val name = TranslationTargetPreference.displayName(forLanguageCode = "da", inLocale = "en")
        assertEquals("Dansk", name.autonym)
        assertEquals("Danish", name.localizedName)
        assertEquals("Danish", name.subtitle)
    }

    @Test
    fun matchingAutonymAndLocalizedNameOmitTheSubtitle() {
        val name = TranslationTargetPreference.displayName(forLanguageCode = "en", inLocale = "en")
        assertNull(name.subtitle)
    }

    @Test
    fun searchMatchesAutonymLocalizedNameAndLanguageCode() {
        assertTrue(TranslationTargetPreference.matchesSearchQuery("dans", languageCode = "da", locale = "en"))
        assertTrue(TranslationTargetPreference.matchesSearchQuery("Danish", languageCode = "da", locale = "en"))
        assertTrue(TranslationTargetPreference.matchesSearchQuery("da", languageCode = "da", locale = "en"))
        assertFalse(TranslationTargetPreference.matchesSearchQuery("French", languageCode = "da", locale = "en"))
    }

    @Test
    fun unavailableSearchResolvesDanishWhenItIsOutsideTheCatalog() {
        assertEquals(
            "da",
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "Danish",
                locale = "en",
                catalog = listOf("en", "de"),
            ),
        )
        assertEquals(
            "da",
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "da",
                locale = "en",
                catalog = listOf("en", "de"),
            ),
        )
        assertEquals(
            "da",
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "Dansk",
                locale = "en",
                catalog = listOf("en", "de"),
            ),
        )
    }

    @Test
    fun unavailableSearchIgnoresCatalogLanguagesShortQueriesAndUnknownText() {
        assertNull(
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "Danish",
                locale = "en",
                catalog = listOf("da", "en"),
            ),
        )
        assertNull(
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "d",
                locale = "en",
                catalog = listOf("en"),
            ),
        )
        assertNull(
            TranslationTargetPreference.unavailableLanguageCode(
                matchingSearchQuery = "xxqq",
                locale = "en",
                catalog = listOf("en"),
            ),
        )
    }

    @Test
    fun detailLabelFollowsTheAppLocaleUntilAnOverrideIsSet() {
        assertEquals(
            "Ukrainian",
            TranslationTargetPreference.matchAppLanguage.detailLabel(
                appLocaleIdentifier = "uk",
                displayLocale = "en",
            ),
        )
        assertEquals(
            "Danish",
            TranslationTargetPreference("da").detailLabel(appLocaleIdentifier = "en", displayLocale = "en"),
        )
    }
}
