package one.meshcore.shared.chat.translation

/** Display name of [code] in [inLocale] (e.g. `da` in `en` → "Danish"). */
expect fun platformLanguageDisplayName(code: String, inLocale: String): String

/** Display name of ISO α-2 [code] in [inLocale] (e.g. `DE` in `de` → "Deutschland"). */
expect fun platformRegionDisplayName(code: String, inLocale: String): String

/** Compact ISO language codes used for unavailable-language search. */
expect fun platformIsoLanguageCodes(): List<String>

/** Compact codes this device can translate on-device. Empty on hosts with no translator. */
expect fun platformOnDeviceTranslationCatalog(): List<String>

/** Device-preferred language tags for Translate Into suggestions (`da-DK`, `en-US`, …). */
expect fun platformPreferredLanguageTags(): List<String>
