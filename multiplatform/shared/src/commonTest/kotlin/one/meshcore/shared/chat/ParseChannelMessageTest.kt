package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors the iOS `SyncCoordinatorMessageHandlerTests` parseChannelMessage family. */
class ParseChannelMessageTest {
    private fun parse(text: String) = MessageIngestPipeline.parseChannelMessage(text)

    @Test
    fun parsesStandardNameTextFormat() {
        val (sender, text) = parse("NodeAlpha: Hello world")
        assertEquals("NodeAlpha", sender)
        assertEquals("Hello world", text)
    }

    @Test
    fun handlesMultipleColons() {
        val (sender, text) = parse("Node: time is 12:30:00")
        assertEquals("Node", sender)
        assertEquals("time is 12:30:00", text)
    }

    @Test
    fun returnsNullSenderForTextWithoutColon() {
        val (sender, text) = parse("just plain text")
        assertNull(sender)
        assertEquals("just plain text", text)
    }

    @Test
    fun returnsNullSenderForEmptyString() {
        val (sender, text) = parse("")
        assertNull(sender)
        assertEquals("", text)
    }

    @Test
    fun handlesColonOnly() {
        val (sender, text) = parse(":")
        assertNull(sender)
        assertEquals(":", text)
    }

    @Test
    fun trimsWhitespaceFromSenderAndText() {
        val (sender, text) = parse("  NodeName  :  hello there  ")
        assertEquals("NodeName", sender)
        assertEquals("hello there", text)
    }

    @Test
    fun handlesColonAtStart() {
        val (sender, text) = parse(": some text")
        assertNull(sender)
        assertEquals(": some text", text)
    }

    @Test
    fun handlesEmojiInName() {
        val (sender, text) = parse("Node🔥: hello")
        assertEquals("Node🔥", sender)
        assertEquals("hello", text)
    }

    @Test
    fun handlesUnicodeCharacters() {
        val (sender, text) = parse("Ñoño: café time")
        assertEquals("Ñoño", sender)
        assertEquals("café time", text)
    }

    @Test
    fun handlesSenderAndColonWithNoText() {
        val (sender, text) = parse("NodeName:")
        assertNull(sender)
        assertEquals("NodeName:", text)
    }
}
