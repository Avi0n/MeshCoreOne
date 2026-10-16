package one.meshcore.shared.chat.translation

/**
 * In-bubble Translate target: `"app"` and empty follow the app locale, `"overlay"` is the system
 * Translate sheet, anything else is a language subtag.
 */
data class TranslationTargetPreference(val rawValue: String) {
    fun resolvedLanguageCode(appLocaleIdentifier: String): String {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty() || trimmed == MATCH_APP_LANGUAGE || trimmed == SYSTEM_OVERLAY) {
            return collapsedAppLanguage(appLocaleIdentifier)
        }
        return MessageLanguageDetector.collapsedLanguageCode(trimmed)
    }

    val isMatchAppLanguage: Boolean
        get() {
            val trimmed = rawValue.trim()
            return trimmed.isEmpty() || trimmed == MATCH_APP_LANGUAGE
        }

    val isSystemOverlay: Boolean
        get() = rawValue.trim() == SYSTEM_OVERLAY

    fun detailLabel(appLocaleIdentifier: String, displayLocale: String): String {
        val code = resolvedLanguageCode(appLocaleIdentifier)
        return localizedName(code, displayLocale)
    }

    data class DisplayName(val autonym: String, val localizedName: String) {
        val subtitle: String?
            get() = if (autonym.equals(localizedName, ignoreCase = true)) null else localizedName
    }

    companion object {
        const val MATCH_APP_LANGUAGE = "app"
        const val SYSTEM_OVERLAY = "overlay"
        const val DEFAULT_APP_LANGUAGE = "en"
        private const val UNAVAILABLE_SEARCH_QUERY_MINIMUM_LENGTH = 2

        val matchAppLanguage = TranslationTargetPreference(MATCH_APP_LANGUAGE)
        val systemOverlay = TranslationTargetPreference(SYSTEM_OVERLAY)

        fun usesSystemOverlay(languageRawValue: String, useDefaultTranslationApp: Boolean): Boolean =
            useDefaultTranslationApp || TranslationTargetPreference(languageRawValue).isSystemOverlay

        fun suggestedLanguageCodes(
            preferredLanguages: List<String>,
            appLocaleIdentifier: String,
            catalog: List<String>,
        ): List<String> {
            val catalogSet = catalog.toSet()
            val app = collapsedAppLanguage(appLocaleIdentifier)
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (identifier in preferredLanguages) {
                val code = MessageLanguageDetector.collapsedLanguageCode(identifier)
                if (code in catalogSet && code != app && seen.add(code)) result += code
            }
            return result
        }

        fun uniqueCompactCodes(from: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (identifier in from) {
                val code = MessageLanguageDetector.collapsedLanguageCode(identifier)
                if (code.isNotEmpty() && seen.add(code)) result += code
            }
            return result
        }

        fun localizedName(forLanguageCode: String, locale: String): String =
            platformLanguageDisplayName(forLanguageCode, inLocale = locale).ifEmpty { forLanguageCode }

        fun displayName(forLanguageCode: String, inLocale: String): DisplayName {
            val rawAutonym = platformLanguageDisplayName(forLanguageCode, inLocale = forLanguageCode)
            val autonym = rawAutonym.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val localized = localizedName(forLanguageCode, locale = inLocale)
            return DisplayName(autonym = autonym, localizedName = localized)
        }

        fun matchesSearchQuery(query: String, languageCode: String, locale: String): Boolean {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return true
            val name = displayName(forLanguageCode = languageCode, inLocale = locale)
            return name.autonym.contains(trimmed, ignoreCase = true) ||
                name.localizedName.contains(trimmed, ignoreCase = true) ||
                languageCode.contains(trimmed, ignoreCase = true)
        }

        fun unavailableLanguageCode(matchingSearchQuery: String, locale: String, catalog: List<String>): String? {
            val trimmed = matchingSearchQuery.trim()
            if (trimmed.length < UNAVAILABLE_SEARCH_QUERY_MINIMUM_LENGTH) return null
            val excluded = catalog.map { MessageLanguageDetector.collapsedLanguageCode(it) }.toSet()
            var exact: String? = null
            val prefixMatches = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (languageCode in platformIsoLanguageCodes()) {
                val compact = MessageLanguageDetector.collapsedLanguageCode(languageCode)
                if (compact.isNotEmpty() && compact !in excluded && seen.add(compact)) {
                    val name = displayName(forLanguageCode = compact, inLocale = locale)
                    if (isExactLanguageMatch(trimmed, compact, name)) {
                        exact = compact
                        break
                    }
                    if (isPrefixLanguageMatch(trimmed, name)) prefixMatches += compact
                }
            }
            return exact ?: prefixMatches.singleOrNull()
        }

        private fun collapsedAppLanguage(appLocaleIdentifier: String): String =
            MessageLanguageDetector.collapsedLanguageCode(appLocaleIdentifier).ifEmpty { DEFAULT_APP_LANGUAGE }

        private fun isExactLanguageMatch(query: String, code: String, name: DisplayName): Boolean =
            name.autonym.equals(query, ignoreCase = true) ||
                name.localizedName.equals(query, ignoreCase = true) ||
                code.equals(query, ignoreCase = true)

        private fun isPrefixLanguageMatch(query: String, name: DisplayName): Boolean =
            name.autonym.startsWith(query, ignoreCase = true) ||
                name.localizedName.startsWith(query, ignoreCase = true)
    }
}
