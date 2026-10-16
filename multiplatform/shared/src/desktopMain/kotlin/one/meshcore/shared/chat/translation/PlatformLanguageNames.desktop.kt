package one.meshcore.shared.chat.translation

actual fun platformOnDeviceTranslationCatalog(): List<String> = emptyList()

actual fun platformPreferredLanguageTags(): List<String> = listOf(java.util.Locale.getDefault().toLanguageTag())
