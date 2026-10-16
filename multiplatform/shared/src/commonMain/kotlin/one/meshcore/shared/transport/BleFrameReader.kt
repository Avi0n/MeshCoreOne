package one.meshcore.shared.transport

/**
 * The inbound framing contract for BLE, ported from the Swift receive path. Per the MeshCore companion
 * radio protocol each BLE notification is a complete frame, so there is deliberately no cross-
 * notification buffering or reassembly (unlike the length-prefixed WiFi/TCP stream, which needs a
 * stateful decoder). The only transformation is dropping empty notifications, mirroring the Swift
 * `handleReceivedData` guard.
 *
 * This is isolated as pure logic so the "each notification is one frame, empties dropped" invariant is
 * unit-testable without a peripheral, and so the contract is stated in one place rather than implied.
 */
object BleFrameReader {
    /**
     * The frame to deliver for one inbound notification: the notification bytes unchanged, or `null`
     * when empty. Notifications are never concatenated or split — two notifications yield two
     * independent frames.
     */
    fun frameFrom(notification: ByteArray): ByteArray? = notification.takeIf { it.isNotEmpty() }
}
