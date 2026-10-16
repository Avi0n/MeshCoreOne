package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the pure path-detail derivation ported from the iOS `MessagePathContent`: the empty state for a
 * message with no path, the sender/intermediate/receiver row order, the receiver being the only row with an
 * SNR, injected repeater-name resolution, and the arrow/comma path strings. Confirms there is no "Direct"
 * row — a zero-hop path renders just sender + receiver.
 */
class MessagePathDisplayTest {
    @Test
    fun noPathNodesYieldsUnavailable() {
        val message = ChatFixtures.message().copy(pathNodes = null)
        val display = MessagePathDisplay.build(
            message = message,
            resolution = MessagePathResolution(
                senderName = "Alice",
                senderNodeID = "A3",
                receiverName = "Me",
                resolveRepeaterName = { null },
            ),
        )
        assertFalse(display.available)
        assertTrue(display.rows.isEmpty())
        assertEquals(0, display.hopCount)
    }

    @Test
    fun buildsSenderIntermediateAndReceiverRows() {
        val message = ChatFixtures.message().copy(
            pathLength = 0x02,
            pathNodes = byteArrayOf(0xA3.toByte(), 0x7F),
            snr = 5.5,
        )
        val display = MessagePathDisplay.build(
            message = message,
            resolution = MessagePathResolution(
                senderName = "Alice",
                senderNodeID = "01",
                receiverName = "Me",
                resolveRepeaterName = { raw -> "repeater-${raw[0].toInt() and 0xFF}" },
            ),
        )

        assertTrue(display.available)
        assertEquals(4, display.rows.size) // sender + 2 hops + receiver

        val sender = display.rows.first()
        assertEquals(PathHopType.Sender, sender.type)
        assertEquals("Alice", sender.nodeName)
        assertNull(sender.snr)

        assertEquals(PathHopType.Intermediate(1), display.rows[1].type)
        assertEquals("A3", display.rows[1].nodeID)
        assertEquals("repeater-163", display.rows[1].nodeName) // 0xA3 == 163, resolver injected
        assertNull(display.rows[1].snr)

        val receiver = display.rows.last()
        assertEquals(PathHopType.Receiver, receiver.type)
        assertEquals("Me", receiver.nodeName)
        assertEquals(5.5, receiver.snr) // only the receiver row carries SNR
        assertNull(receiver.nodeID)

        assertTrue(display.hasIntermediateHops)
        assertEquals(2, display.hopCount)
        assertEquals("A3 → 7F", display.pathString)
        assertEquals("A3,7F", display.pathStringForClipboard)
    }

    @Test
    fun zeroHopPathRendersSenderAndReceiverOnlyWithNoDirectRow() {
        val message = ChatFixtures.message().copy(pathLength = 0x00, pathNodes = byteArrayOf(), snr = null)
        val display = MessagePathDisplay.build(
            message = message,
            resolution = MessagePathResolution(
                senderName = "Alice",
                senderNodeID = "01",
                receiverName = "Me",
                resolveRepeaterName = { null },
            ),
        )

        assertTrue(display.available)
        assertEquals(listOf(PathHopType.Sender, PathHopType.Receiver), display.rows.map { it.type })
        assertFalse(display.hasIntermediateHops)
        assertEquals(0, display.hopCount)
    }
}
