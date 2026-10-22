// PortedFrom: MC1Services/Tests/MC1ServicesTests/DeduplicationKeyTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageDeduplicationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/AckCodeBuilderTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/BLETransportOpenedSignalTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceConfigTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Utilities/ChannelRXCorrelationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SourceValuesTest {
    @TestFactory fun values() = listOf(
        original("DeduplicationKeyTests", "Content-based key uses the expected prefix for DM and channel scopes",
            "(contactID : UUID ? , channelIndex : UInt8 ? , senderNodeName : String ? , timestamp : UInt32 , content : String , expectedPrefix : String)", " [row=0]") {
            assertTrue(DeduplicationKey.contentBased(CONTACT, null, null, 1000u, "hello").startsWith("dm-AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE-1000-"))
        },
        original("DeduplicationKeyTests", "Content-based key uses the expected prefix for DM and channel scopes",
            "(contactID : UUID ? , channelIndex : UInt8 ? , senderNodeName : String ? , timestamp : UInt32 , content : String , expectedPrefix : String)", " [row=1]") {
            assertTrue(DeduplicationKey.contentBased(null, 3u, "Alice", 2000u, "test").startsWith("ch-3-2000-Alice-"))
        },
        original("DeduplicationKeyTests", "Content-based key uses the expected prefix for DM and channel scopes",
            "(contactID : UUID ? , channelIndex : UInt8 ? , senderNodeName : String ? , timestamp : UInt32 , content : String , expectedPrefix : String)", " [row=2]") {
            assertTrue(DeduplicationKey.contentBased(null, 0u, null, 500u, "msg").startsWith("ch-0-500--"))
        },
        original("DeduplicationKeyTests", "Content-based key uses the expected prefix for DM and channel scopes",
            "(contactID : UUID ? , channelIndex : UInt8 ? , senderNodeName : String ? , timestamp : UInt32 , content : String , expectedPrefix : String)", " [row=3]") {
            assertTrue(DeduplicationKey.contentBased(null, null, null, 100u, "x").startsWith("dm-unknown-100-"))
        },
        original("DeduplicationKeyTests", "Content-based key has an 8-char hex hash suffix") {
            assertEquals(8, DeduplicationKey.contentBased(CONTACT, null, null, 1000u, "hello").substringAfterLast('-').length)
        },
        original("DeduplicationKeyTests", "Content-based key is deterministic and distinguishes different content") {
            val key = DeduplicationKey.contentBased(CONTACT, null, null, 1u, "aaa")
            assertEquals(key, DeduplicationKey.contentBased(CONTACT, null, null, 1u, "aaa"))
            assertNotEquals(key, DeduplicationKey.contentBased(CONTACT, null, null, 1u, "bbb"))
        },
        original("DeduplicationKeyTests", "Empty content still produces a well-formed key") {
            val key = DeduplicationKey.contentBased(CONTACT, null, null, 0u, "")
            assertTrue(key.startsWith("dm-")); assertTrue(key.length > 10)
        },
        original("MessageDeduplicationTests", "DM fallback key is deterministic") {
            val key = DeduplicationKey.contentBased(RADIO.value, null, null, 1_704_067_200u, "Hello world")
            assertEquals(key, DeduplicationKey.contentBased(RADIO.value, null, null, 1_704_067_200u, "Hello world"))
            assertTrue(key.startsWith("dm-"))
        },
        original("MessageDeduplicationTests", "Channel fallback key is deterministic") {
            val key = DeduplicationKey.contentBased(null, 0u, "Alice", 1_704_067_200u, "Hello channel")
            assertEquals(key, DeduplicationKey.contentBased(null, 0u, "Alice", 1_704_067_200u, "Hello channel"))
            assertTrue(key.startsWith("ch-"))
        },
        original("MessageDeduplicationTests", "DM and channel fallback keys never collide for same content") {
            assertNotEquals(DeduplicationKey.contentBased(RADIO.value, null, null, 1_704_067_200u, "Hello"),
                DeduplicationKey.contentBased(null, 0u, "Alice", 1_704_067_200u, "Hello"))
        },
        original("MessageDeduplicationTests", "Different contacts produce different DM fallback keys") {
            assertNotEquals(DeduplicationKey.contentBased(RADIO.value, null, null, 1_704_067_200u, "Hello"),
                DeduplicationKey.contentBased(PEER_RADIO.value, null, null, 1_704_067_200u, "Hello"))
        },
        original("MessageDeduplicationTests", "Different channel indices produce different channel fallback keys") {
            assertNotEquals(DeduplicationKey.contentBased(null, 0u, "Alice", 1_704_067_200u, "Hello"),
                DeduplicationKey.contentBased(null, 1u, "Alice", 1_704_067_200u, "Hello"))
        },
        original("AckCodeBuilderTests", "matches firmware formula for known fixture") {
            assertEquals(Bytes.of(0x5E, 0x39, 0x9E, 0x8A),
                AckCodeBuilder.expectedAck(0x6624_AABBu, 2u, "hello", Bytes(ByteArray(32) { 0xAA.toByte() })))
        },
        original("AckCodeBuilderTests", "different texts produce different codes") {
            assertNotEquals(AckCodeBuilder.expectedAck(1u, 0u, "hi", SELF), AckCodeBuilder.expectedAck(1u, 0u, "bye", SELF))
        },
        original("AckCodeBuilderTests", "attempts 0..3 produce four distinct codes") {
            val publicKey = Bytes(ByteArray(32) { 0x02 })
            assertEquals(4, (0..3).map { AckCodeBuilder.expectedAck(100u, it.toUByte(), "hi", publicKey) }.toSet().size)
        },
        original("AckCodeBuilderTests", "attempt 4 wraps to attempt 0's code") {
            val publicKey = Bytes(ByteArray(32) { 0x03 })
            assertEquals(AckCodeBuilder.expectedAck(100u, 0u, "hi", publicKey),
                AckCodeBuilder.expectedAck(100u, 4u, "hi", publicKey))
        },
        original("BLETransportOpenedSignalTests", "wait returns immediately when signal already armed") {
            val signal = BLETransportOpenedSignal(); signal.fire(); signal.wait(); assertEquals(0, signal.waiterCount)
        },
        original("BLETransportOpenedSignalTests", "wait suspends until fire") {
            val signal = BLETransportOpenedSignal()
            val waiting = async { signal.wait() }; advanceTimeBy(50); runCurrent(); assertFalse(waiting.isCompleted)
            signal.fire(); waiting.await(); assertEquals(0, signal.waiterCount)
        },
        original("BLETransportOpenedSignalTests", "wait throws CancellationError when calling task is cancelled") {
            val signal = BLETransportOpenedSignal()
            val waiting = async { signal.wait() }; advanceTimeBy(50); runCurrent(); waiting.cancelAndJoin()
            assertFailsWith<CancellationException> { waiting.await() }; assertEquals(0, signal.waiterCount)
        },
        original("BLETransportOpenedSignalTests", "fire after cancellation does not double-resume") {
            val signal = BLETransportOpenedSignal()
            val waiting = async { signal.wait() }; advanceTimeBy(50); runCurrent(); waiting.cancelAndJoin()
            signal.fire(); signal.wait(); assertEquals(0, signal.waiterCount)
        },
        original("MessageServiceConfigTests", "maxAttempts == 5 is accepted at the precondition boundary") { assertEquals(5L, MessageServiceConfig(maxAttempts = 5).maxAttempts) },
        original("MessageServiceConfigTests", "Default config respects the maxAttempts ceiling") { assertTrue(MessageServiceConfig().maxAttempts <= 5) },
        original("MessageServiceTests", "MessageServiceConfig default values") {
            val c = MessageServiceConfig()
            assertTrue(c.floodFallbackOnRetry); assertEquals(5L, c.maxAttempts); assertEquals(1L, c.maxFloodAttempts)
            assertEquals(4L, c.floodAfter); assertEquals(0.0, c.minTimeout); assertTrue(c.triggerPathDiscoveryAfterFlood); assertEquals(30.0, c.ackGiveUpWindow)
        },
        original("MessageServiceTests", "MessageServiceConfig custom values") {
            val c = MessageServiceConfig(false, 3, 3, 1, 10.0, false)
            assertFalse(c.floodFallbackOnRetry); assertEquals(3L, c.maxAttempts); assertEquals(3L, c.maxFloodAttempts)
            assertEquals(1L, c.floodAfter); assertEquals(10.0, c.minTimeout); assertFalse(c.triggerPathDiscoveryAfterFlood)
        },
        original("ChannelRXCorrelationTests", "keeps the 0x88 whose body matches the message key") {
            val matches = ChannelRXCorrelation.matching(listOf(rx("Bob", "two", 0xB2), rx("Alice", "one", 0xA1)), channelKey())
            assertEquals(listOf(Bytes.of(0xA1)), matches.map { it.pathNodes })
        },
        original("ChannelRXCorrelationTests", "sorts matches by receivedAt ascending") {
            val matches = ChannelRXCorrelation.matching(listOf(
                rx("Alice", "one", 0xA1, Instant.ofEpochSecond(2000)),
                rx("Alice", "one", 0xA2, Instant.ofEpochSecond(1000))), channelKey())
            assertEquals(listOf(Bytes.of(0xA2), Bytes.of(0xA1)), matches.map { it.pathNodes })
        },
        original("ChannelRXCorrelationTests", "drops entries without decodedText") {
            val a = rx("Alice", "one", 0xA1)
            val missingText = rx("Alice", "one", 0xC3).copy(decodedText = null)
            val matches = ChannelRXCorrelation.matching(listOf(missingText, a), channelKey())
            assertEquals(listOf(Bytes.of(0xA1)), matches.map { it.pathNodes })
            assertEquals(listOf(a), matches)
        },
        original("ChannelRXCorrelationTests", "nil key matches nothing") { assertTrue(ChannelRXCorrelation.matching(listOf(rx("Alice", "one", 0xA1)), null).isEmpty()) },
        original("ChannelRXCorrelationTests", "matches when decoded sender has trailing space") {
            val key = DeduplicationKey.contentBased(null, 0u, "Alice", 42u, "hello")
            assertEquals(listOf(Bytes.of(0xA1)), ChannelRXCorrelation.matching(listOf(rx("Alice ", "hello", 0xA1)), key).map { it.pathNodes })
        },
    )
    private fun channelKey() = DeduplicationKey.contentBased(null, 0u, "Alice", 42u, "one")
    private fun rx(sender: String, body: String, path: Int, receivedAt: Instant = BASE_TIME) = RxLogEntryDTO(
        UUID.randomUUID(), RADIO, receivedAt, 8.0, -70, RouteType.FLOOD, PayloadType.GROUP_TEXT, 0u, null,
        1u, Bytes.of(path), Bytes.of(1, 2, 3), Bytes.of(1), "039058c6f2c0cb49", channelIndex = 0u, channelName = "Test",
        decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = 42u, payloadTypeBits = 5u, decodedText = "$sender: $body",
    )
}
