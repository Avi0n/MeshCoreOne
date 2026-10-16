package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EmojiCatalogTest {
    private val catalog = EmojiCatalog.parse(SAMPLE)

    @Test
    fun searchMatchesLabelShortcodeAndTag() {
        assertEquals(
            listOf("😀"),
            catalog.categories("grinning", frequent = emptyList()).single().emojis.map {
                it.unicode
            },
        )
        assertEquals(
            listOf("😀"),
            catalog.categories("grin", frequent = emptyList()).single().emojis.map {
                it.unicode
            },
        )
        assertEquals(listOf("🐶"), catalog.categories("dog", frequent = emptyList()).single().emojis.map { it.unicode })
    }

    @Test
    fun searchHidesFrequentAndEmptyCategories() {
        val ids = catalog.categories("grin", frequent = listOf("👍")).map { it.id }
        assertFalse(ids.contains("frequent"))
        assertEquals(listOf("people"), ids)
    }

    @Test
    fun emptyQueryKeepsFrequentThenCategories() {
        val ids = catalog.categories("", frequent = listOf("👍")).map { it.id }
        assertEquals(listOf("frequent", "people", "nature"), ids)
    }

    @Test
    fun diacriticSearchMatchesAccentedLabel() {
        val cafe = EmojiCatalog.parse(
            """{"people":[{"unicode":"☕","label":"café","shortcodes":[],"tags":[]}]}""",
        )
        assertEquals(listOf("☕"), cafe.categories("cafe", frequent = emptyList()).single().emojis.map { it.unicode })
    }
}

private const val SAMPLE = """
{
  "people": [
    {"unicode":"😀","label":"grinning face","shortcodes":["grinning"],"tags":["grin","smile"]}
  ],
  "nature": [
    {"unicode":"🐶","label":"dog face","shortcodes":["dog"],"tags":["pet"]}
  ]
}
"""
