package one.meshcore.shared.settings.ui

import one.meshcore.shared.chat.translation.MessageLanguageDetector
import one.meshcore.shared.chat.translation.TranslationTargetPreference

/** Rows the Translate Into screen shows for the current query. */
internal data class TranslateIntoSearch(val searching: Boolean, val matches: List<String>, val unavailable: String?)

/** The current language stays listed unless the choice is match-app or the system sheet. */
internal fun translateIntoCatalogCodes(
    selected: TranslationTargetPreference,
    onDeviceCodes: List<String>,
): List<String> {
    val codes = TranslationTargetPreference.uniqueCompactCodes(onDeviceCodes).toMutableList()
    if (!selected.isMatchAppLanguage && !selected.isSystemOverlay) {
        val current = MessageLanguageDetector.collapsedLanguageCode(selected.rawValue)
        if (codes.none { MessageLanguageDetector.isSameLanguage(it, current) }) {
            codes += current
        }
    }
    return codes
}

internal fun translateIntoRemainingCodes(
    catalog: List<String>,
    suggested: List<String>,
    displayLocale: String,
): List<String> {
    val suggestedSet = suggested.toSet()
    return catalog.filter { it !in suggestedSet }.sortedByTranslateIntoAutonym(displayLocale)
}

internal fun translateIntoSearch(query: String, catalog: List<String>, displayLocale: String): TranslateIntoSearch {
    val searching = query.isNotBlank()
    val matches = catalog
        .filter { TranslationTargetPreference.matchesSearchQuery(query, it, displayLocale) }
        .sortedByTranslateIntoAutonym(displayLocale)
    val unavailable = if (searching && matches.isEmpty()) {
        TranslationTargetPreference.unavailableLanguageCode(
            matchingSearchQuery = query,
            locale = displayLocale,
            catalog = catalog,
        )
    } else {
        null
    }
    return TranslateIntoSearch(searching = searching, matches = matches, unavailable = unavailable)
}

internal fun translateIntoSearchIsEmpty(search: TranslateIntoSearch): Boolean =
    search.searching && search.matches.isEmpty() && search.unavailable == null

private fun List<String>.sortedByTranslateIntoAutonym(displayLocale: String): List<String> = sortedBy {
    TranslationTargetPreference.displayName(forLanguageCode = it, inLocale = displayLocale).autonym.lowercase()
}
