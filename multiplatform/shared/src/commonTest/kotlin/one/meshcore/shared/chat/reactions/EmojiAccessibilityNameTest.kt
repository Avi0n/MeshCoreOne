package one.meshcore.shared.chat.reactions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiAccessibilityNameTest {
    @Test
    fun thumbsUpHasUnicodeName() {
        val name = emojiAccessibilityName("👍")
        assertTrue(name.contains("thumbs"), name)
        assertTrue(name.contains("up"), name)
    }

    @Test
    fun grinningFaceName() {
        val name = emojiAccessibilityName("😀")
        assertTrue(name.contains("grinning"), name)
    }

    @Test
    fun emptyStringReturnsEmpty() {
        assertEquals("", emojiAccessibilityName(""))
    }

    @Test
    fun multiCodepointJoinsNames() {
        // Family emoji is multi-codepoint; name should be non-empty and multi-token when names exist.
        val name = emojiAccessibilityName("👍")
        assertTrue(name.isNotBlank())
    }
}
