package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.model.decodeHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the RX-log decrypt entry point through the real `libMeshCoreBridgeKit.so` on a device.
 *
 * The pure-JVM suites can only prove the envelopes; this one proves the crypto itself lands where
 * the golden fixtures say it does, with the same live-radio channel packet and RFC 7748 §6.1 key
 * pair the Swift side is frozen against.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Raw packets and lengths are the bridge test vectors.
class RxLogBridgeDecryptInstrumentedTest {

    private val decryptor = run {
        installAndroidRxLogDecryptor()
        platformRxLogDecryptor()
    }

    private val channelPayload = hex("aba0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")
    private val channelSecret = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")
    private val wrongSecret = hex("5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a")
    private val directPayload = hex("1122056ae2b357549ef909cda0d3c1367faf8e9b")
    private val bobPrivateKey = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val alicePublicKey = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

    private fun channelRow(vararg keys: RxLogDecryptKey) = RxLogDecryptRow.Channel(channelPayload, keys.toList())

    @Test
    fun theLiveRadioChannelVectorDecryptsThroughTheBridge() {
        val row = channelRow(RxLogDecryptKey(1, wrongSecret), RxLogDecryptKey(3, channelSecret))
        val result = decryptor.decrypt(listOf(row)).single()

        val success = result as RxLogDecryptResult.Success
        assertEquals(3, success.keyIndex)
        assertEquals("Alice: Hello mesh!", success.text)
        assertEquals(1_703_123_456L, success.senderTimestamp)
    }

    @Test
    fun theRfc7748DirectVectorDecryptsThroughTheBridge() {
        val row = RxLogDecryptRow.Direct(directPayload, bobPrivateKey, listOf(RxLogDecryptKey(0, alicePublicKey)))
        val success = decryptor.decrypt(listOf(row)).single() as RxLogDecryptResult.Success

        assertEquals(0, success.keyIndex)
        assertEquals("Hello mesh!", success.text)
        assertEquals(1_700_000_000L, success.senderTimestamp)
    }

    @Test
    fun everyOutcomeCrossesTheBoundaryInOrder() {
        val results = decryptor.decrypt(
            listOf(
                channelRow(RxLogDecryptKey(3, channelSecret)),
                RxLogDecryptRow.Channel(hex("ab0001"), emptyList()),
                channelRow(RxLogDecryptKey(7, wrongSecret)),
            ),
        )

        assertEquals(3, results.size)
        assertTrue(results[0] is RxLogDecryptResult.Success)
        assertEquals(RxLogDecryptResult.PayloadTooShort, results[1])
        assertEquals(RxLogDecryptResult.NoMatchingKey, results[2])
    }

    /** A full re-derivation pass preserves all decrypted fields compared with singleton calls. */
    @Test
    fun aFullLogMatchesRowAtATime() {
        val rows = List(MAX_ROWS) { channelRow(RxLogDecryptKey(3, channelSecret)) }
        val batched = decryptor.decrypt(rows)
        val perRow = rows.map { decryptor.decrypt(listOf(it)).single() }

        assertEquals(MAX_ROWS, batched.count { it is RxLogDecryptResult.Success })
        batched.forEachIndexed { index, result ->
            val expected = result as RxLogDecryptResult.Success
            val actual = perRow[index] as RxLogDecryptResult.Success
            assertEquals(expected.keyIndex, actual.keyIndex)
            assertEquals(expected.senderTimestamp, actual.senderTimestamp)
            assertEquals(expected.text, actual.text)
        }
    }

    private fun hex(text: String): ByteArray = requireNotNull(decodeHex(text))

    private companion object {
        /** The live RX-log buffer cap, so the test covers the worst re-derivation pass. */
        const val MAX_ROWS = 1000
    }
}
