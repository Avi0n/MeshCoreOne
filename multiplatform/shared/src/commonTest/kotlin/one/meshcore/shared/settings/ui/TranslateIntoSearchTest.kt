package one.meshcore.shared.settings.ui

import one.meshcore.shared.chat.translation.TranslationTargetPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslateIntoSearchTest {
    private val onDevice = listOf("en-US", "de-DE")

    @Test
    fun selectedLanguageJoinsTheCatalogWhenTheDeviceListOmitsIt() {
        val codes = translateIntoCatalogCodes(TranslationTargetPreference("fr-FR"), onDevice)
        assertEquals(listOf("en", "de", "fr"), codes)
    }

    @Test
    fun matchAppAndOverlayStayOffTheCatalog() {
        assertEquals(listOf("en", "de"), translateIntoCatalogCodes(TranslationTargetPreference("app"), onDevice))
        assertEquals(listOf("en", "de"), translateIntoCatalogCodes(TranslationTargetPreference("overlay"), onDevice))
        assertEquals(listOf("en", "de"), translateIntoCatalogCodes(TranslationTargetPreference(""), onDevice))
    }

    @Test
    fun selectedLanguageAlreadyInTheCatalogIsNotRepeated() {
        val codes = translateIntoCatalogCodes(TranslationTargetPreference("en-GB"), onDevice)
        assertEquals(listOf("en", "de"), codes)
    }

    @Test
    fun remainingCodesDropSuggestedLanguagesAndSortByAutonym() {
        val remaining = translateIntoRemainingCodes(
            catalog = listOf("fr", "en", "da", "de"),
            suggested = listOf("en"),
            displayLocale = "en",
        )
        assertEquals(listOf("da", "de", "fr"), remaining)
    }

    @Test
    fun blankQueryIsNotASearch() {
        val search = translateIntoSearch(query = "  ", catalog = listOf("en", "de"), displayLocale = "en")
        assertFalse(search.searching)
        assertEquals(listOf("de", "en"), search.matches)
        assertNull(search.unavailable)
        assertFalse(translateIntoSearchIsEmpty(search))
    }

    @Test
    fun unknownQueryWithNoLanguageIsAnEmptySearch() {
        val search = translateIntoSearch(query = "zzzz", catalog = listOf("en", "de"), displayLocale = "en")
        assertTrue(search.searching)
        assertEquals(emptyList(), search.matches)
        assertNull(search.unavailable)
        assertTrue(translateIntoSearchIsEmpty(search))
    }

    @Test
    fun namedLanguageMissingFromTheCatalogIsUnavailable() {
        val search = translateIntoSearch(query = "dansk", catalog = listOf("en", "de"), displayLocale = "da")
        assertTrue(search.searching)
        assertEquals(emptyList(), search.matches)
        assertEquals("da", search.unavailable)
        assertFalse(translateIntoSearchIsEmpty(search))
    }

    @Test
    fun matchingQueryKeepsTheCatalogRow() {
        val search = translateIntoSearch(query = "deutsch", catalog = listOf("en", "de", "fr"), displayLocale = "en")
        assertEquals(listOf("de"), search.matches)
        assertNull(search.unavailable)
        assertFalse(translateIntoSearchIsEmpty(search))
    }
}
