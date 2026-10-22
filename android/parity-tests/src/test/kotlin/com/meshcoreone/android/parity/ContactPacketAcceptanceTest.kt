// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/UpdateContactTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactPacketAcceptanceTest {
    @OriginalCase("UpdateContactTests::updateContact produces 147 bytes()")
    @OriginalCase("UpdateContactTests::updateContact correct layout()")
    @OriginalCase("UpdateContactTests::updateContact signed path length()")
    @Test fun originalContactPacketFamiliesKeepTheirDistinctInputs() {
        val chat = MeshContact("test", repeated(0xAA, 32), ContactType.CHAT, ContactFlags(2u),
            3u, Bytes.of(0x11, 0x22, 0x33), "TestNode", Instant.ofEpochSecond(1_704_067_200),
            37.7749, -122.4194, Instant.EPOCH)
        val packet = PacketBuilder.updateContact(chat)
        assertEquals(147, packet.size)
        assertEquals(Bytes.of(0x09) + repeated(0xAA, 32) + Bytes.of(1, 2, 3) +
            Bytes.of(0x11, 0x22, 0x33) + repeated(0, 61) +
            Bytes.utf8("TestNode") + repeated(0, 24) +
            little32(1_704_067_200) + little32(37_774_900) + little32(-122_419_400) +
            repeated(0, 3), packet)

        val room = MeshContact("test", repeated(0xAA, 32), ContactType.ROOM, ContactFlags(3u),
            3u, Bytes.of(0x11, 0x22, 0x33), "Node", Instant.ofEpochSecond(1_000),
            10.0, -20.0, Instant.EPOCH)
        assertEquals(Bytes.of(0x09) + repeated(0xAA, 32) + Bytes.of(3, 3, 3) +
            Bytes.of(0x11, 0x22, 0x33) + repeated(0, 61) +
            Bytes.utf8("Node") + repeated(0, 28) +
            little32(1_000) + little32(10_000_000) + little32(-20_000_000) +
            repeated(0, 3), PacketBuilder.updateContact(room))

        val flood = MeshContact("test", repeated(0, 32), ContactType.CHAT, ContactFlags(0u),
            0xFFu, Bytes.EMPTY, "", Instant.EPOCH, 0.0, 0.0, Instant.EPOCH)
        val flooded = PacketBuilder.updateContact(flood)
        assertEquals(0xFFu.toUByte(), flooded[35])
        assertEquals(Bytes.of(0x09) + repeated(0, 32) + Bytes.of(1, 0, 0xFF) +
            repeated(0, 111), flooded)
    }

    @OriginalCase("LegacyUpdateContactHardeningTests::changeContactFlags with a NaN coordinate does not trap and clamps the frame()")
    @Test fun originalPublicFlagsSeamSaturatesAndWaitsForAcknowledgement() = runTest {
        val clock = object : SessionClock {
            override val now: Duration get() = testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val transport = MockTransport()
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"),
            clock, backgroundScope.coroutineContext)
        try {
            val starting = backgroundScope.async { session.start() }
            runCurrent()
            assertEquals(listOf(Bytes.fromHex("010320202020202054657374")), transport.sentData)
            assertFalse(starting.isCompleted)
            transport.simulateReceive(Bytes(ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN)
                .put(0x05).put(ByteArray(3)).put(ByteArray(32) { 1 })
                .putInt(0).putInt(0).put(ByteArray(4))
                .putInt(915_000).putInt(125_000).put(7).put(5)
                .put("Test".toByteArray(Charsets.UTF_8)).array()))
            runCurrent()
            starting.await()
            transport.clearSentData()
            val contact = MeshContact("x", repeated(0xAB, 32), ContactType.CHAT, ContactFlags(0u),
                0xFFu, Bytes.EMPTY, "N", Instant.ofEpochSecond(-1),
                Double.NaN, 200.0, Instant.EPOCH)
            val call = backgroundScope.async { session.changeContactFlags(contact, ContactFlags(2u)) }
            runCurrent()
            val frame = transport.sentData.single()
            assertEquals(144, frame.size)
            assertEquals(Bytes.of(0x09) + repeated(0xAB, 32) + Bytes.of(1, 2, 0xFF) +
                repeated(0, 64) + Bytes.utf8("N") + repeated(0, 31) +
                little32(0) + little32(0) + little32(180_000_000), frame)
            assertEquals(0u, frame.readUInt32LE(132))
            assertEquals(0, frame.readInt32LE(136))
            assertEquals(180_000_000, frame.readInt32LE(140))
            assertFalse(call.isCompleted)
            advanceTimeBy(100)
            runCurrent()
            assertFalse("The public flags seam cannot succeed before OK", call.isCompleted)
            transport.simulateOK()
            runCurrent()
            call.await()
            assertEquals(1, transport.sentData.size)
            assertEquals(ContactFlags(0u), contact.flags)
        } finally {
            session.stop()
        }
    }

    private fun repeated(value: Int, count: Int): Bytes = Bytes(ByteArray(count) { value.toByte() })
    private fun little32(value: Int): Bytes =
        Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
}
