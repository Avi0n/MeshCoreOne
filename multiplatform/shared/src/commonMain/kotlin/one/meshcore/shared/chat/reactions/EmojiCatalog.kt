package one.meshcore.shared.chat.reactions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics
import one.meshcore.shared.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** One emoji in the Emojibase catalog (matrix-org/emojibase-bindings). */
data class EmojiItem(val unicode: String, val label: String, val shortcodes: List<String>, val tags: List<String>)

/** One category of the picker, matching iOS `EmojiCategoryData`. */
data class EmojiCategoryData(val id: String, val emojis: List<EmojiItem>)

/**
 * Emojibase catalog: category grids plus search over label, shortcodes, and tags, matching iOS
 * `EmojiProvider.categories(searchQuery:)`.
 */
class EmojiCatalog(private val byCategory: Map<String, List<EmojiItem>>) {
    fun categories(searchQuery: String?, frequent: List<String>): List<EmojiCategoryData> {
        val query = searchQuery?.trim().orEmpty()
        val result = mutableListOf<EmojiCategoryData>()
        if (query.isEmpty() && frequent.isNotEmpty()) {
            result += EmojiCategoryData(
                id = "frequent",
                emojis = frequent.map {
                    EmojiItem(unicode = it, label = "", shortcodes = emptyList(), tags = emptyList())
                },
            )
        }
        for (id in CATEGORY_ORDER) {
            val emojis = byCategory[id].orEmpty()
            val filtered = if (query.isEmpty()) {
                emojis
            } else {
                emojis.filter { it.matches(query) }
            }
            if (filtered.isNotEmpty()) {
                result += EmojiCategoryData(id = id, emojis = filtered)
            }
        }
        return result
    }

    fun allUnicodes(): List<String> = CATEGORY_ORDER.flatMap { id -> byCategory[id].orEmpty().map { it.unicode } }

    companion object {
        val CATEGORY_ORDER: List<String> = listOf(
            "people",
            "nature",
            "foods",
            "activity",
            "places",
            "objects",
            "symbols",
            "flags",
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): EmojiCatalog {
            val decoded = json.decodeFromString<Map<String, List<EmojiJson>>>(raw)
            val mapped = decoded.mapValues { (_, rows) ->
                rows.map { row ->
                    EmojiItem(
                        unicode = row.unicode,
                        label = row.label,
                        shortcodes = row.shortcodes,
                        tags = row.tags,
                    )
                }
            }
            return EmojiCatalog(mapped)
        }

        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(): EmojiCatalog {
            cached?.let { return it }
            return parse(Res.readBytes("files/emojibase.json").decodeToString()).also { cached = it }
        }

        @Volatile
        private var cached: EmojiCatalog? = null
    }
}

@Serializable
private data class EmojiJson(
    val unicode: String,
    val label: String,
    val shortcodes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

private fun EmojiItem.matches(query: String): Boolean = label.containsIgnoringCaseAndDiacritics(query) ||
    shortcodes.any { it.containsIgnoringCaseAndDiacritics(query) } ||
    tags.any { it.containsIgnoringCaseAndDiacritics(query) }
