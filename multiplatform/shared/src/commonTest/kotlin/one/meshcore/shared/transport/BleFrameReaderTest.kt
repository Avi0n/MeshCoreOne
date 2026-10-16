package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The BLE inbound framing contract: each notification is one complete frame, empties dropped, and no
 * cross-notification buffering — the mirror of the Swift receive path.
 */
class BleFrameReaderTest {
    @Test
    fun aNonEmptyNotificationIsDeliveredUnchanged() {
        val notification = byteArrayOf(0x05, 0x01, 0x02, 0x03)
        assertContentEquals(notification, BleFrameReader.frameFrom(notification))
    }

    @Test
    fun anEmptyNotificationIsDropped() {
        assertNull(BleFrameReader.frameFrom(ByteArray(0)))
    }

    @Test
    fun twoNotificationsYieldTwoIndependentFramesWithNoConcatenation() {
        val first = byteArrayOf(0x07, 0xAA.toByte())
        val second = byteArrayOf(0x08, 0xBB.toByte())
        assertContentEquals(first, BleFrameReader.frameFrom(first))
        assertContentEquals(second, BleFrameReader.frameFrom(second))
    }
}
