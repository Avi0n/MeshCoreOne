package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every emoji this app can send must parse back as a reaction. One the picker offers but the parser
 * rejects is sent as ordinary text, so the sender and every peer read "🆘\n7ABCDE12" as a message.
 */
class ReactionEmojiCoverageTest {
    private val targetText = "status?"
    private val targetTimestamp = 1_700_000_000L

    private fun dmParses(emoji: String) =
        ReactionParser.parseDM(ReactionParser.buildDmReactionText(emoji, targetText, targetTimestamp))

    private fun channelParses(emoji: String) =
        ReactionParser.parse(ReactionParser.buildReactionText(emoji, "Alice", targetText, targetTimestamp))

    @Test
    fun `every picker emoji round-trips through both grammars`() {
        val offered = (RecentEmojisStore.EXTENDED_EMOJIS + sampleCatalogUnicodes()).distinct()
        val rejected = offered.filter { dmParses(it) == null || channelParses(it) == null }
        assertTrue(rejected.isEmpty(), "these emoji would be sent as raw text: $rejected")
    }

    @Test
    fun `emoji outside the pictographic planes are recognized`() {
        listOf("⏰", "‼️", "㊙️", "🆘", "1️⃣", "Ⓜ️", "↔️").forEach { emoji ->
            assertNotNull(dmParses(emoji), "$emoji must parse as a DM reaction")
        }
    }

    @Test
    fun `plain text that merely looks like the wire form is not a reaction`() {
        // The keycap rule keeps a bare digit from opening the grammar.
        assertNull(ReactionParser.parseDM("5\nABCDEFGH"))
        assertNull(ReactionParser.parseDM("meet at 5\nABCDEFGH"))
        assertNull(ReactionParser.parseDM("\"quoted\"\nABCDEFGH"))
    }
}

private fun sampleCatalogUnicodes(): List<String> = EmojiCatalog.parse(
    """
    {
      "people":[{"unicode":"😀","label":"grinning face","shortcodes":[],"tags":[]}],
      "symbols":[{"unicode":"🆘","label":"SOS button","shortcodes":["sos"],"tags":[]}],
      "flags":[{"unicode":"🇺🇸","label":"flag United States","shortcodes":["flag_us"],"tags":[]}]
    }
    """,
).allUnicodes()
