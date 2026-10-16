package one.meshcore.shared.chat.translation

actual object PlatformMessageTranslation {
    actual suspend fun identify(text: String): Pair<String, Double>? = null

    actual suspend fun translate(text: String, sourceLang: String, targetLang: String): String =
        throw UnsupportedOperationException("On-device translation is not available on desktop")
}

actual fun platformPreferredLanguage(): String = java.util.Locale.getDefault().language
