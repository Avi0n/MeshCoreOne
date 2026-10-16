package one.meshcore.shared.chat.pathdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ports the Swift `HeardRepeatsServiceTests` `ChannelMessageFormat.parse` cases: valid split, no colon,
 * leading colon, empty body, colons in the body, whitespace trimming, and spaces preserved in the sender.
 */
class ChannelMessageFormatTest {
    @Test
    fun validFormatReturnsSenderAndMessage() {
        val result = ChannelMessageFormat.parse("NodeName: Hello world")
        assertEquals("NodeName", result?.senderName)
        assertEquals("Hello world", result?.messageText)
    }

    @Test
    fun noColonReturnsNull() {
        assertNull(ChannelMessageFormat.parse("No colon here"))
    }

    @Test
    fun colonAtStartReturnsNull() {
        assertNull(ChannelMessageFormat.parse(": Message without sender"))
    }

    @Test
    fun emptyMessageReturnsEmptyBody() {
        val result = ChannelMessageFormat.parse("Sender:")
        assertEquals("Sender", result?.senderName)
        assertEquals("", result?.messageText)
    }

    @Test
    fun onlyFirstColonSplits() {
        val result = ChannelMessageFormat.parse("Sender: Time is 10:30:00")
        assertEquals("Sender", result?.senderName)
        assertEquals("Time is 10:30:00", result?.messageText)
    }

    @Test
    fun bodyWhitespaceIsTrimmed() {
        val result = ChannelMessageFormat.parse("Node:   Padded message   ")
        assertEquals("Padded message", result?.messageText)
    }

    @Test
    fun senderSpacesPreserved() {
        val result = ChannelMessageFormat.parse("Node With Spaces: Message")
        assertEquals("Node With Spaces", result?.senderName)
    }
}
