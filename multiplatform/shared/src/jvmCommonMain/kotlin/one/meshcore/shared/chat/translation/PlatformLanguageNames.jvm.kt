package one.meshcore.shared.chat.translation

import java.util.Locale

actual fun platformLanguageDisplayName(code: String, inLocale: String): String {
    val language = Locale.forLanguageTag(code)
    val display = Locale.forLanguageTag(inLocale)
    val name = language.getDisplayLanguage(display)
    return name.ifEmpty { code }
}

actual fun platformRegionDisplayName(code: String, inLocale: String): String {
    val region = Locale.Builder().setRegion(code).build()
    val display = Locale.forLanguageTag(inLocale)
    val name = region.getDisplayCountry(display)
    return name.ifEmpty { code }
}

actual fun platformIsoLanguageCodes(): List<String> = Locale.getISOLanguages().toList()
