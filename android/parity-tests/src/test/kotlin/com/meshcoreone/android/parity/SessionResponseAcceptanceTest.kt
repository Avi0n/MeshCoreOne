// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115SessionMethodsTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionGetChannelsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.FloodScope
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionResponseAcceptanceTest {
    @OriginalCase("V115SessionMethodsTests::sendChannelData emits correct frame and awaits OK()")
    @OriginalCase("V115SessionMethodsTests::sendChannelData forwards pathLength and pathBytes to the builder()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope with name and key()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope clears with empty args()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope FloodScope overload \u2014 disabled clears()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope FloodScope overload \u2014 channelName derives key()")
    @Test fun originalCommandsWaitForDelayedAcknowledgement() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            delayedOk(transport, "3e01ffffff010203") {
                session.sendChannelData(1u, 0xffffu, hex("010203"))
            }
            delayedOk(transport, "3e0003112233ffff42") {
                session.sendChannelData(0u, 0xffffu, hex("42"), 3u, hex("112233"))
            }
            delayedOk(transport, "3f4575726f7065" + "00".repeat(25) + "5a".repeat(16)) {
                session.setDefaultFloodScope("Europe", hex("5a".repeat(16)))
            }
            delayedOk(transport, "3f") { session.setDefaultFloodScope("", Bytes.EMPTY) }
            delayedOk(transport, "3f") { session.setDefaultFloodScope("ignored", FloodScope.Disabled) }
            delayedOk(transport, "3f707562" + "00".repeat(28) + "efa1f375d76194fa51a3556a97e641e6") {
                session.setDefaultFloodScope("pub", FloodScope.ChannelName("public"))
            }
            assertEquals("Startup plus exactly six acknowledged writes", 7, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("V115SessionMethodsTests::sendChannelData throws deviceError on error response()")
    @OriginalCase("V115SessionMethodsTests::getDefaultFloodScope propagates device errors()")
    @Test fun originalDeviceErrorsRemainTypedAfterDelayedResponse() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            delayedError(transport, "3e00ff010042", 2u) {
                session.sendChannelData(0u, 1u, hex("42"))
            }
            delayedError(transport, "40", 1u) { session.getDefaultFloodScope() }
            assertEquals("Startup plus exactly two failed writes", 3, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @Test fun noResponseRetainsSourceTenSecondTimeout() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            val call = async {
                try {
                    session.sendChannelData(1u, 0xffffu, hex("010203"))
                    error("A missing acknowledgment cannot become success")
                } catch (failure: MeshCoreException.Timeout) {
                    failure
                }
            }
            runCurrent()
            assertEquals(hex("3e01ffffff010203"), transport.sentData.last())
            advanceTimeBy(9_999)
            runCurrent()
            assertFalse(call.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(call.await() is MeshCoreException.Timeout)
            assertEquals(2, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::no more than the window is outstanding before the first response, then refills()")
    @Test fun originalWindowHoldsForFiftyMillisecondsAndRefillsWithoutMissingSlots() = runTest {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val configuration = sourcePipelineConfiguration(window = 2)
        val session = startSourceSession(transport, configuration, sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { session.getChannels(listOf(0u, 1u, 2u, 3u)) }
            runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f01")), transport.sentData.drop(1))
            advanceTimeBy(50)
            runCurrent()
            assertEquals("The original fifty-millisecond window must not issue a third read", 3, transport.sentData.size)
            assertFalse(call.isCompleted)
            transport.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            assertEquals(4, transport.sentData.size)
            assertEquals(hex("1f02"), transport.sentData.last())
            transport.simulateReceive(sourceChannel(1, "one"))
            runCurrent()
            assertEquals(5, transport.sentData.size)
            assertEquals(hex("1f03"), transport.sentData.last())
            transport.simulateReceive(sourceChannel(2, "two"))
            transport.simulateReceive(sourceChannel(3, "three"))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), result.received.map { it.index })
            assertTrue(result.missing.isEmpty())
            assertEquals(5, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::a cancelled getChannels does not leak a late channelInfo into a same-type successor()")
    @Test fun originalCancelledPipelineKeepsTheSuccessorsIndexAndFreshName() = runTest {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val configuration = sourcePipelineConfiguration(idleTimeout = 0.1)
        val session = startSourceSession(transport, configuration, sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val pipeline = backgroundScope.async { session.getChannels(listOf(0u, 1u)) }
            runCurrent()
            assertEquals(3, transport.sentData.size)
            pipeline.cancel()
            runCurrent()
            assertTrue(pipeline.isCancelled)
            transport.simulateReceive(sourceChannel(0, "late", 0xAA))
            runCurrent()
            val successor = backgroundScope.async { session.getChannel(0u) }
            runCurrent()
            assertEquals("Successor must wait for the old exchange slot", 3, transport.sentData.size)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(4, transport.sentData.size)
            assertEquals(hex("1f00"), transport.sentData.last())
            assertFalse("The late response cannot complete the successor", successor.isCompleted)
            transport.simulateReceive(sourceChannel(0, "fresh", 0xBB))
            runCurrent()
            val result = successor.await()
            assertEquals(0u.toUByte(), result.index)
            assertEquals("fresh", result.name)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::each requested index lands in its own slot and unrequested indexes are ignored()")
    @OriginalCase("MeshCoreSessionGetChannelsTests::dropped writes surface as missing indexes after the idle timeout()")
    @OriginalCase("MeshCoreSessionGetChannelsTests::falls back to serial reads when the transport lacks write-without-response()")
    @Test fun originalChannelOrderingMissingAndSerialFamiliesKeepTheirExactInputs() = runTest {
        val outOfOrder = MockTransport()
        outOfOrder.setSupportsWriteWithoutResponse(true)
        val first = startSourceSession(outOfOrder, sourcePipelineConfiguration(),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { first.getChannels(listOf(0u, 2u, 7u)) }
            runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f02"), hex("1f07")), outOfOrder.sentData.drop(1))
            outOfOrder.simulateReceive(sourceChannel(7, "seven", 0x77))
            outOfOrder.simulateReceive(sourceChannel(2, "two", 0x22))
            outOfOrder.simulateReceive(sourceChannel(5, "five", 0x55))
            outOfOrder.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val result = call.await()
            assertTrue(result.missing.isEmpty())
            assertEquals(listOf<UByte>(0u, 2u, 7u), result.received.map { it.index })
            assertEquals(listOf("zero", "two", "seven"), result.received.map { it.name })
            assertFalse(result.received.any { it.index == 5u.toUByte() })
        } finally {
            first.stop()
        }

        val dropped = MockTransport()
        dropped.setSupportsWriteWithoutResponse(true)
        val second = startSourceSession(dropped, sourcePipelineConfiguration(idleTimeout = 0.1),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { second.getChannels(listOf(0u, 1u, 2u, 3u)) }
            runCurrent()
            assertEquals(5, dropped.sentData.size)
            dropped.simulateReceive(sourceChannel(0, "zero"))
            dropped.simulateReceive(sourceChannel(1, "one"))
            dropped.simulateReceive(sourceChannel(3, "three"))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u, 3u), result.received.map { it.index })
            assertEquals(listOf<UByte>(2u), result.missing)
        } finally {
            second.stop()
        }

        val serial = MockTransport()
        val third = startSourceSession(serial, sourcePipelineConfiguration(),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { third.getChannels(listOf(0u, 1u)) }
            runCurrent()
            assertEquals(2, serial.sentData.size)
            assertEquals(hex("1f00"), serial.sentData.last())
            serial.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            assertEquals(3, serial.sentData.size)
            assertEquals(hex("1f01"), serial.sentData.last())
            serial.simulateReceive(sourceChannel(1, "one"))
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u), result.received.map { it.index })
            assertTrue(result.missing.isEmpty())
        } finally {
            third.stop()
        }
    }

    private fun sourcePipelineConfiguration(window: Long = 8, idleTimeout: Double = 1.5) =
        SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst",
            channelPipelineWindow = window, channelPipelineIdleTimeout = idleTimeout,
            channelPipelineHardTimeout = 5.0, channelPipelinePostDrainGrace = 0.02)

    private suspend fun TestScope.startSourceSession(
        transport: MockTransport,
        configuration: SessionConfiguration = SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"),
        selfInfo: Bytes = sourceSelfInfo(869_525, 250_000, 11, ""),
    ): MeshCoreSession {
        val session = MeshCoreSession(transport, configuration, publicSessionClock(), backgroundScope.coroutineContext)
        val starting = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(1, transport.sentData.size)
        assertFalse(starting.isCompleted)
        transport.simulateReceive(selfInfo)
        runCurrent()
        starting.await()
        assertNotNull(session.currentSelfInfo)
        return session
    }

    private fun sourceSelfInfo(frequency: Int, bandwidth: Int, spreadingFactor: Byte, name: String): Bytes {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        return Bytes(ByteBuffer.allocate(58 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x05).put(1).put(22).put(22).put(ByteArray(32) { 1 })
            .putInt(0).putInt(0).put(ByteArray(4))
            .putInt(frequency).putInt(bandwidth).put(spreadingFactor).put(5).put(nameBytes).array())
    }

    private fun sourceChannel(index: Int, name: String, secret: Int = 0): Bytes =
        Bytes.of(0x12, index) + Bytes.utf8(name) + Bytes(ByteArray(32 - name.length)) +
            Bytes(ByteArray(16) { secret.toByte() })

    private suspend fun TestScope.delayedOk(
        transport: MockTransport, expected: String, operation: suspend () -> Unit,
    ) {
        val call = backgroundScope.async { operation() }
        runCurrent()
        assertEquals(hex(expected), transport.sentData.last())
        assertFalse("Written command must still await OK", call.isCompleted)
        transport.simulateReceive(hex("0ce803"))
        advanceTimeBy(100)
        runCurrent()
        assertFalse("An unrelated event is not an acknowledgment", call.isCompleted)
        transport.simulateOK()
        runCurrent()
        call.await()
    }

    private suspend fun TestScope.delayedError(
        transport: MockTransport, expected: String, code: UByte, operation: suspend () -> Any?,
    ) {
        val call = backgroundScope.async {
            try {
                operation()
                error("A missing/error response cannot become success or nullable absence")
            } catch (failure: MeshCoreException.DeviceError) {
                failure
            }
        }
        runCurrent()
        assertEquals(hex(expected), transport.sentData.last())
        advanceTimeBy(100)
        runCurrent()
        assertFalse(call.isCompleted)
        transport.simulateError(code)
        runCurrent()
        assertEquals(code, call.await().code)
    }

    private fun hex(value: String): Bytes = Bytes.fromHex(value)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.publicSessionClock(): SessionClock = object : SessionClock {
    override val now: Duration get() = testScheduler.currentTime.milliseconds
    override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    override suspend fun sleepFor(duration: Duration) = delay(duration)
}
