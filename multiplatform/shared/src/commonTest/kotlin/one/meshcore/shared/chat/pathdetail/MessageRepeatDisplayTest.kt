package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.map.SnrQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises the [MessageRepeatDto] display accessors ported from the Swift `MessageRepeatDTO` computed
 * properties: hash size, last-repeater hash, hop count, hex formatting, and the SNR/RSSI display strings.
 */
class MessageRepeatDisplayTest {
    private fun repeat(
        pathNodes: ByteArray,
        pathLength: Int,
        snr: Double? = null,
        rssi: Int? = null,
    ): MessageRepeatDto = MessageRepeatDto(
        id = ChatFixtures.freshUuid(),
        messageID = ChatFixtures.freshUuid(),
        receivedAt = 1_700_000_000.0,
        pathNodes = pathNodes,
        pathLength = pathLength,
        snr = snr,
        rssi = rssi,
    )

    @Test
    fun singleByteHashDerivesRepeaterAndHopCount() {
        val entry = repeat(pathNodes = byteArrayOf(0x31, 0xA7.toByte()), pathLength = 0x02)
        assertEquals(1, entry.hashSize)
        assertEquals(2, entry.hopCount)
        assertEquals("A7", entry.repeaterHashFormatted) // last hop is the node we heard from
        assertEquals(listOf("31", "A7"), entry.pathNodesHex)
    }

    @Test
    fun twoByteHashTakesLastTwoBytesAsRepeater() {
        val entry = repeat(pathNodes = byteArrayOf(0x31, 0xA7.toByte(), 0x42, 0x11), pathLength = 0x42)
        assertEquals(2, entry.hashSize)
        assertEquals(2, entry.hopCount)
        assertEquals("4211", entry.repeaterHashFormatted)
    }

    @Test
    fun emptyPathFormatsRepeaterAsDoubleZero() {
        val entry = repeat(pathNodes = byteArrayOf(), pathLength = 0x00)
        assertNull(entry.repeaterHash)
        assertEquals("00", entry.repeaterHashFormatted)
        assertEquals(0, entry.hopCount)
    }

    @Test
    fun snrLevelMapsToBucket() {
        assertEquals(SnrQuality.EXCELLENT, repeat(byteArrayOf(0x31), 0x01, snr = 8.0).snrQuality)
        assertEquals(1.0, repeat(byteArrayOf(0x31), 0x01, snr = 8.0).snrLevel)
        assertEquals(0.0, repeat(byteArrayOf(0x31), 0x01, snr = null).snrLevel)
    }

    @Test
    fun snrAndRssiFormatDisplayStrings() {
        val entry = repeat(byteArrayOf(0x31), 0x01, snr = -3.0, rssi = -70)
        assertEquals("-3.0 dB", entry.snrFormatted("dB"))
        assertEquals("-70 dBm", entry.rssiFormatted("dBm"))
        assertEquals("-3.0 дБ", entry.snrFormatted("дБ"))
        assertEquals("-70 дБм", entry.rssiFormatted("дБм"))
    }

    @Test
    fun missingSnrAndRssiUseThePlaceholder() {
        val entry = repeat(byteArrayOf(0x31), 0x01, snr = null, rssi = null)
        assertEquals("—", entry.snrFormatted("dB"))
        assertEquals("—", entry.rssiFormatted("dBm"))
    }
}
