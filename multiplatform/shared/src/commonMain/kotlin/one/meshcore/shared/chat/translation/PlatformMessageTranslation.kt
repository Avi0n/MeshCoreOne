package one.meshcore.shared.chat.translation

/** On-device language ID and translation. Android uses ML Kit; desktop leaves both unimplemented. */
expect object PlatformMessageTranslation {
    suspend fun identify(text: String): Pair<String, Double>?
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
}

expect fun platformPreferredLanguage(): String
