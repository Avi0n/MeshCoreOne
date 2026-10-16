package one.meshcore.shared.chat.reactions

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipped Emojibase catalog must round-trip through [ReactionParser]. An emoji the picker can
 * send but the parser rejects travels as ordinary text.
 */
class EmojiCatalogCoverageTest {
    @Test
    fun everyShippedCatalogEmojiRoundTrips() {
        val catalog = EmojiCatalog.parse(emojibaseFile().readText())
        val offered = catalog.allUnicodes()
        assertTrue(offered.size > 1_000, "catalog too small: ${offered.size}")
        val targetText = "status?"
        val targetTimestamp = 1_700_000_000L
        val rejected = offered.filter { emoji ->
            ReactionParser.parseDM(ReactionParser.buildDmReactionText(emoji, targetText, targetTimestamp)) == null ||
                ReactionParser.parse(ReactionParser.buildReactionText(emoji, "Alice", targetText, targetTimestamp)) ==
                null
        }
        assertTrue(rejected.isEmpty(), "these emoji would be sent as raw text: $rejected")
    }
}

private fun emojibaseFile(): File {
    val candidates = listOf(
        File("src/commonMain/composeResources/files/emojibase.json"),
        File("multiplatform/shared/src/commonMain/composeResources/files/emojibase.json"),
    )
    return candidates.first { it.exists() }
}
