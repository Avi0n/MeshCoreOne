package one.meshcore.shared.meshcore

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogPayloadType
import one.meshcore.shared.tools.processRxLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The decode cache is constructed at process start, before [MeshCoreSession] installs the Android
 * bridge. Capturing the unavailable default there leaves every group-text RX-log row PENDING, so
 * hop hashes never correlate onto chat messages.
 */
class PlatformBridgeForwardingTest {
    @Test
    fun platformDecryptorCapturedBeforeInstallUsesTheBridgeInstalledLater() {
        val previous = PlatformBridge.rxLogDecryptor
        try {
            PlatformBridge.rxLogDecryptor = UnavailableRxLogDecryptor
            val captured = platformRxLogDecryptor()
            val later = FakeRxLogDecryptor(
                listOf(FakeDecryptKey(SECRET, PLAINTEXT, SENDER_TIMESTAMP)),
            )
            PlatformBridge.rxLogDecryptor = later
            val row = RxLogDecryptRow.Channel(payload = byteArrayOf(1), keys = listOf(RxLogDecryptKey(0, SECRET)))
            val result = captured.decrypt(listOf(row)).single()
            assertIs<RxLogDecryptResult.Success>(result)
            assertEquals(SENDER_TIMESTAMP, result.senderTimestamp)
        } finally {
            PlatformBridge.rxLogDecryptor = previous
        }
    }

    @Test
    fun aDecodeCacheBuiltBeforeInstallDecryptsAfterInstall() = runTest {
        val previous = PlatformBridge.rxLogDecryptor
        try {
            PlatformBridge.rxLogDecryptor = UnavailableRxLogDecryptor
            val cache = RxLogDecodeCache()
            PlatformBridge.rxLogDecryptor = FakeRxLogDecryptor(
                listOf(FakeDecryptKey(SECRET, PLAINTEXT, SENDER_TIMESTAMP)),
            )
            cache.updateChannels(
                listOf(
                    ChannelDto(
                        id = Uuid.normalized("C0000000-0000-0000-0000-000000000009"),
                        radioID = RADIO,
                        index = 0,
                        name = "Public",
                        secret = SECRET,
                        isEnabled = true,
                        unreadCount = 0,
                    ),
                ),
            )
            val entry = processRxLogEntry(
                RADIO,
                groupTextFrame(),
                receivedAtEpochMs = 10L,
                decode = cache.snapshot(),
            )
            assertEquals(DecryptStatus.SUCCESS, entry.decryptStatus)
            assertEquals(0, entry.channelIndex)
            assertEquals(SENDER_TIMESTAMP, entry.senderTimestamp)
            assertTrue(entry.pathNodes.isNotEmpty())
        } finally {
            PlatformBridge.rxLogDecryptor = previous
        }
    }

    private fun groupTextFrame(): ParsedRxLogData = ParsedRxLogData(
        snr = null,
        rssi = null,
        rawPayload = ByteArray(0),
        routeType = 0,
        payloadType = RxLogPayloadType.GROUP_TEXT,
        payloadVersion = 0,
        payloadTypeBits = RxLogPayloadType.GROUP_TEXT,
        transportCode = null,
        pathLength = 3,
        pathNodes = listOf(0xA3, 0x7F, 0x42),
        packetPayload = CHANNEL_VECTOR_PAYLOAD,
        packetHash = "hops",
    )

    private companion object {
        val RADIO = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val SECRET = byteArrayOf(
            0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(), 0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
            0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(), 0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
        )
        const val PLAINTEXT = "Alice: Hello mesh!"
        const val SENDER_TIMESTAMP = 1_703_123_456L
        val CHANNEL_VECTOR_PAYLOAD = byteArrayOf(
            0xA0.toByte(), 0xE6.toByte(), 0x21, 0x55, 0x65, 0xCF.toByte(), 0x05, 0x55,
            0xD9.toByte(), 0xE4.toByte(), 0xA5.toByte(), 0xE4.toByte(), 0x82.toByte(), 0x4D, 0xC7.toByte(), 0x00,
            0x18, 0xA6.toByte(), 0x02, 0x1E, 0x82.toByte(), 0x32, 0xBB.toByte(), 0x05,
        )
    }
}
