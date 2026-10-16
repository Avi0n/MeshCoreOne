package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ported from the Swift `MessagePathFormatterTests` truncation/direct/flood cases. */
class MessagePathFormatterTest {
    private fun message(pathLength: Int, pathNodes: ByteArray?) = ChatFixtures.message(
        direction = MessageDirection.INCOMING,
    ).copy(pathLength = pathLength, pathNodes = pathNodes)

    @Test
    fun `direct-routed message formats as the direct label`() {
        val message = message(pathLength = ChatConstants.FLOOD_PATH_SENTINEL, pathNodes = null)
        assertEquals("Direct", MessagePathFormatter.format(message, "Direct", "Flood"))
    }

    @Test
    fun `single destination-marker byte formats as the direct label`() {
        val message = message(pathLength = 0x01, pathNodes = byteArrayOf(0xFF.toByte()))
        assertEquals("Direct", MessagePathFormatter.format(message, "Direct", "Flood"))
    }

    @Test
    fun `flood-routed message with no path nodes formats as the flood label`() {
        val message = message(pathLength = 0, pathNodes = null)
        assertEquals("Flood", MessagePathFormatter.format(message, "Direct", "Flood"))
    }

    @Test
    fun `short hop chain joins hex nodes with commas`() {
        val message = message(pathLength = 0x03, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42))
        assertEquals("A3,7F,42", MessagePathFormatter.format(message, "Direct", "Flood"))
    }

    @Test
    fun `long hop chain collapses to first three and last three nodes`() {
        val nodes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42, 0x11, 0x22, 0xB2.toByte(), 0xC1.toByte())
        val message = message(pathLength = 0x07, pathNodes = nodes)
        assertEquals("A3,7F,42…22,B2,C1", MessagePathFormatter.format(message, "Direct", "Flood"))
    }
}
