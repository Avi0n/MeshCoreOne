package one.meshcore.shared.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CI-style key parity for Compose multiplatform string resources: every locale folder must declare the
 * same `name=` set as English `values/strings.xml`. Mirrors the SwiftGen gate that prevents a locale from
 * falling back to raw key strings. Fixture paths are loaded from the multiplatform module resource tree
 * via [loadComposeStringsXml].
 */
class ComposeLocaleKeyParityTest {
    @Test
    fun `all locales declare the same string keys as English`() {
        val english = parseKeys(loadComposeStringsXml("values"))
        assertTrue(english.isNotEmpty(), "English values/strings.xml must not be empty")
        for (locale in LOCALES) {
            val keys = parseKeys(loadComposeStringsXml(locale))
            val missing = english - keys
            val extra = keys - english
            assertEquals(
                emptySet<String>(),
                missing,
                "$locale missing ${missing.size} keys (sample: ${missing.take(SAMPLE).joinToString()})",
            )
            assertEquals(
                emptySet<String>(),
                extra,
                "$locale has ${extra.size} extra keys (sample: ${extra.take(SAMPLE).joinToString()})",
            )
        }
    }

    @Test
    fun `folded Android supplement keys are present in every locale`() {
        // The Android-only `android_*` keys (onboarding Bluetooth card, path-editor hop actions,
        // Settings/Tools hub subtitles) were folded from the old English-only strings_android.xml into
        // the translated supplement, so they now ride strings.xml and fall under the parity check above.
        val english = parseKeys(loadComposeStringsXml("values"))
        val androidKeys = english.filter { it.startsWith(ANDROID_PREFIX) }.toSet()
        assertTrue(
            androidKeys.size >= MIN_ANDROID_KEYS,
            "expected at least $MIN_ANDROID_KEYS folded Android keys, found ${androidKeys.size}",
        )
        for (locale in LOCALES) {
            val missing = androidKeys - parseKeys(loadComposeStringsXml(locale))
            assertEquals(
                emptySet<String>(),
                missing,
                "$locale missing folded Android keys (sample: ${missing.take(SAMPLE).joinToString()})",
            )
        }
    }

    private fun parseKeys(xml: String): Set<String> = KEY_REGEX.findAll(xml).map { it.groupValues[1] }.toSet()

    private companion object {
        val LOCALES = listOf(
            "values-de",
            "values-es",
            "values-fr",
            "values-nl",
            "values-pl",
            "values-ru",
            "values-uk",
            "values-zh-rCN",
            "values-it",
            "values-pt",
        )
        val KEY_REGEX = Regex("""<string\s+name="([^"]+)"""")
        const val SAMPLE = 5
        const val ANDROID_PREFIX = "android_"
        const val MIN_ANDROID_KEYS = 28
    }
}
