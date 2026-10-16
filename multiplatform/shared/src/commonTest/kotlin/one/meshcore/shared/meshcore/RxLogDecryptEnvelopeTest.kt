package one.meshcore.shared.meshcore

import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Kotlin half of the RX-log decrypt freeze. The rows below are the same ones the Swift producer
 * encodes into `fixtures/bridge/rxlog/decrypt-request.json`, so encoding them here must reproduce
 * that file byte for byte; the response fixture must then decode to the outcomes the Swift side
 * recorded. The vectors are real: a channel packet captured off a live radio, and the RFC 7748 §6.1
 * Alice/Bob key pair.
 */
class RxLogDecryptEnvelopeTest {

    private val channelPayload = hex("aba0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")
    private val channelSecret = hex("8b3387e9c5cdea6ac9e5edbaa115cd72")
    private val wrongSecret = hex("5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a")
    private val directPayload = hex("1122056ae2b357549ef909cda0d3c1367faf8e9b")
    private val bobPrivateKey = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val alicePublicKey = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

    private val goldenRows: List<RxLogDecryptRow> = listOf(
        RxLogDecryptRow.Channel(
            payload = channelPayload,
            keys = listOf(RxLogDecryptKey(1, wrongSecret), RxLogDecryptKey(3, channelSecret)),
        ),
        RxLogDecryptRow.Direct(
            payload = directPayload,
            privateKey = bobPrivateKey,
            keys = listOf(RxLogDecryptKey(0, alicePublicKey)),
        ),
        RxLogDecryptRow.Channel(payload = hex("ab0001"), keys = emptyList()),
        RxLogDecryptRow.Channel(payload = channelPayload, keys = listOf(RxLogDecryptKey(7, wrongSecret))),
    )

    @Test
    fun requestEncodesToTheGoldenVector() {
        val golden = readBridgeFixtures("rxlog").getValue("decrypt-request").trimEnd('\n')
        assertEquals(golden, encodeRxLogDecryptRequest(goldenRows).decodeToString())
    }

    @Test
    fun goldenResponseDecodesToTheRecordedOutcomes() {
        val golden = readBridgeFixtures("rxlog").getValue("decrypt-response")
        val results = decodeRxLogDecryptResponse(golden, goldenRows.size)

        assertEquals(goldenRows.size, results.size)
        val channel = assertIs<RxLogDecryptResult.Success>(results[0])
        assertEquals(3, channel.keyIndex, "the matching key's own index comes back, not its position")
        assertEquals("Alice: Hello mesh!", channel.text)
        assertEquals(1_703_123_456L, channel.senderTimestamp)

        val direct = assertIs<RxLogDecryptResult.Success>(results[1])
        assertEquals(0, direct.keyIndex)
        assertEquals("Hello mesh!", direct.text)
        assertEquals(1_700_000_000L, direct.senderTimestamp)

        assertEquals(RxLogDecryptResult.PayloadTooShort, results[2])
        assertEquals(RxLogDecryptResult.NoMatchingKey, results[3])
    }

    @Test
    fun failureEnvelopeLeavesEveryRowUnavailable() {
        val failure = """{"kind":"rxLogDecryptFailure","payload":{"errorCode":10,"message":"bad request"},"v":1}"""
        val results = decodeRxLogDecryptResponse(failure, rowCount = 3)

        assertEquals(3, results.size)
        results.forEach { assertTrue(assertIs<RxLogDecryptResult.Unavailable>(it).reason.contains("bad request")) }
    }

    @Test
    fun aShortResultListIsPaddedRatherThanMisaligned() {
        val short = """{"kind":"rxLogDecrypt","payload":{"results":[{"outcome":"noMatchingKey"}]},"v":1}"""
        val results = decodeRxLogDecryptResponse(short, rowCount = 3)

        assertEquals(RxLogDecryptResult.NoMatchingKey, results[0])
        assertIs<RxLogDecryptResult.Unavailable>(results[1])
        assertIs<RxLogDecryptResult.Unavailable>(results[2])
    }

    @Test
    fun anUnknownEnvelopeKindIsRejected() {
        val alien = """{"kind":"somethingElse","payload":{},"v":1}"""
        assertFailsWith<BridgeDecodingException> { decodeRxLogDecryptResponse(alien, rowCount = 1) }
    }

    private fun hex(text: String): ByteArray = requireNotNull(decodeHex(text))
}
