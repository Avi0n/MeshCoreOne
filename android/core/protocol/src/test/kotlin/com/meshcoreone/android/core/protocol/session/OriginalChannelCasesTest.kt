// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionGetChannelsTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115SessionMethodsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.FloodScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalChannelCasesTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("MeshCoreSessionGetChannelsTests", "each requested index lands in its own slot and unrequested indexes are ignored") {
            val f = fixture(transport = SessionRadioTransport(true)); start(f)
            val task = backgroundScope.async { f.session.getChannels(listOf(0u, 2u, 7u)) }; runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f02"), hex("1f07")), f.transport.sent.drop(1))
            f.transport.receive(channelPacket(7, "seven", 0x77))
            f.transport.receive(channelPacket(2, "two", 0x22))
            f.transport.receive(channelPacket(5, "five", 0x55))
            f.transport.receive(channelPacket(0, "zero")); runCurrent()
            advanceTimeBy(20); runCurrent()
            val result = task.await()
            assertTrue(result.missing.isEmpty())
            assertEquals(listOf<UByte>(0u, 2u, 7u), result.received.map { it.index })
            assertEquals(listOf("zero", "two", "seven"), result.received.map { it.name })
            f.session.stop()
        },
        original("MeshCoreSessionGetChannelsTests", "dropped writes surface as missing indexes after the idle timeout") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst", channelPipelineWindow = 8,
                channelPipelineIdleTimeout = 0.1, channelPipelineHardTimeout = 5.0,
                channelPipelinePostDrainGrace = 0.02,
            ), SessionRadioTransport(true))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = backgroundScope.async { f.session.getChannels(listOf(0u, 1u, 2u, 3u)) }; runCurrent()
            assertEquals(5, f.transport.sent.size)
            f.transport.receive(channelPacket(0, "zero")); f.transport.receive(channelPacket(1, "one"))
            f.transport.receive(channelPacket(3, "three")); runCurrent()
            advanceTimeBy(99); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(task.isCompleted)
            assertEquals(listOf<UByte>(0u, 1u, 3u), task.await().received.map { it.index })
            assertEquals(listOf<UByte>(2u), task.await().missing)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        original("MeshCoreSessionGetChannelsTests", "no more than the window is outstanding before the first response, then refills") {
            val configuration = SessionConfiguration(
                clientIdentifier = "MCore", channelPipelineWindow = 2,
                channelPipelineIdleTimeout = 0.1, channelPipelinePostDrainGrace = 0.02,
            )
            val f = fixture(configuration, SessionRadioTransport(true)); start(f)
            val task = backgroundScope.async { f.session.getChannels(listOf(0u, 1u, 2u, 3u)) }; runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f01")), f.transport.sent.drop(1))
            f.transport.receive(channelPacket(0)); runCurrent()
            assertEquals(hex("1f02"), f.transport.sent.last())
            f.transport.receive(channelPacket(1)); runCurrent()
            assertEquals(hex("1f03"), f.transport.sent.last())
            f.transport.receive(channelPacket(2)); f.transport.receive(channelPacket(3)); runCurrent()
            advanceTimeBy(20); runCurrent()
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), task.await().received.map { it.index })
            assertEquals(5, f.transport.sent.size)
            f.session.stop()
        },
        original("MeshCoreSessionGetChannelsTests", "a cancelled getChannels does not leak a late channelInfo into a same-type successor") {
            val f = fixture(transport = SessionRadioTransport(true)); start(f)
            val pipeline = backgroundScope.async { f.session.getChannels(listOf(0u, 1u)) }; runCurrent()
            pipeline.cancel(); runCurrent()
            assertFailsWith<CancellationException> { pipeline.await() }
            f.transport.receive(channelPacket(0, "late", 0xaa)); runCurrent()
            val successor = backgroundScope.async { f.session.getChannel(0u) }; runCurrent()
            assertEquals(3, f.transport.sent.size)
            advanceTimeBy(200); runCurrent()
            assertEquals(4, f.transport.sent.size)
            assertFalse(successor.isCompleted)
            f.transport.receive(channelPacket(0, "fresh", 0xbb)); runCurrent()
            assertEquals("fresh", successor.await().name)
            f.session.stop()
        },
        original("MeshCoreSessionGetChannelsTests", "falls back to serial reads when the transport lacks write-without-response") {
            val f = fixture(); start(f)
            val task = backgroundScope.async { f.session.getChannels(listOf(0u, 1u)) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(channelPacket(0, "zero")); runCurrent()
            assertEquals(3, f.transport.sent.size)
            f.transport.receive(channelPacket(1, "one")); runCurrent()
            assertEquals(listOf<UByte>(0u, 1u), task.await().received.map { it.index })
            assertTrue(task.await().missing.isEmpty())
            assertEquals(0, f.transport.unacknowledgedWrites)
            f.session.stop()
        },
        original("V115SessionMethodsTests", "sendChannelData emits correct frame and awaits OK") {
            val f = fixture(); start(f)
            command(f, hex("3e01ffffff010203"), hex("00")) {
                f.session.sendChannelData(1u, 0xffffu, hex("010203"))
            }
            f.session.stop()
        },
        original("V115SessionMethodsTests", "sendChannelData throws deviceError on error response") {
            val f = fixture(); start(f)
            assertEquals(2u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> {
                command(f, hex("3e00ff010042"), hex("0102")) { f.session.sendChannelData(0u, 1u, hex("42")) }
            }.code)
            f.session.stop()
        },
        original("V115SessionMethodsTests", "sendChannelData forwards pathLength and pathBytes to the builder") {
            val f = fixture(); start(f)
            command(f, hex("3e0003112233ffff42"), hex("00")) {
                f.session.sendChannelData(0u, 0xffffu, hex("42"), 3u, hex("112233"))
            }
            f.session.stop()
        },
        original("V115SessionMethodsTests", "setDefaultFloodScope with name and key") {
            val f = fixture(); start(f)
            val expected = raw(0x3f, Bytes.utf8("Europe").paddedOrTruncated(31) + filled(0x5a, 16))
            command(f, expected, hex("00")) { f.session.setDefaultFloodScope("Europe", filled(0x5a, 16)) }
            assertEquals(48, f.transport.sent.last().size)
            f.session.stop()
        },
        original("V115SessionMethodsTests", "setDefaultFloodScope clears with empty args") {
            val f = fixture(); start(f)
            command(f, hex("3f"), hex("00")) { f.session.setDefaultFloodScope("", Bytes.EMPTY) }
            f.session.stop()
        },
        original("V115SessionMethodsTests", "setDefaultFloodScope FloodScope overload \u2014 disabled clears") {
            val f = fixture(); start(f)
            command(f, hex("3f"), hex("00")) { f.session.setDefaultFloodScope("ignored", FloodScope.Disabled) }
            f.session.stop()
        },
        original("V115SessionMethodsTests", "setDefaultFloodScope FloodScope overload \u2014 channelName derives key") {
            val f = fixture(); start(f)
            val independentPublicKey = hex("efa1f375d76194fa51a3556a97e641e6")
            command(f, raw(0x3f, Bytes.utf8("pub").paddedOrTruncated(31) + independentPublicKey), hex("00")) {
                f.session.setDefaultFloodScope("pub", FloodScope.ChannelName("public"))
            }
            f.session.stop()
        },
        original("V115SessionMethodsTests", "getDefaultFloodScope returns populated scope") {
            val f = fixture(); start(f)
            val response = raw(0x1c, Bytes.utf8("NA").paddedOrTruncated(31) + filled(0xc3, 16))
            val scope = assertNotNull(command(f, hex("40"), response) { f.session.getDefaultFloodScope() })
            assertEquals("NA", scope.name)
            assertEquals(filled(0xc3, 16), scope.scopeKey)
            f.session.stop()
        },
        original("V115SessionMethodsTests", "getDefaultFloodScope returns nil when empty") {
            val f = fixture(); start(f)
            assertNull(command(f, hex("40"), hex("1c")) { f.session.getDefaultFloodScope() })
            f.session.stop()
        },
        original("V115SessionMethodsTests", "getDefaultFloodScope propagates device errors") {
            val f = fixture(); start(f)
            assertEquals(1u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> {
                command(f, hex("40"), hex("0101")) { f.session.getDefaultFloodScope() }
            }.code)
            f.session.stop()
        },
        original("V115SessionMethodsTests", "sendTrace throws invalidInput when path is nil") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.sendTrace() }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        original("V115SessionMethodsTests", "sendTrace throws invalidInput when path is empty") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.sendTrace(path = Bytes.EMPTY) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
    )
}
