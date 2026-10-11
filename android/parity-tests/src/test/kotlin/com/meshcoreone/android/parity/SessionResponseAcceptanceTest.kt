// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115SessionMethodsTests.swift@db14559b39d32322b06477c6ae676112f583db50
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

    private suspend fun TestScope.startSourceSession(transport: MockTransport): MeshCoreSession {
        val clock = object : SessionClock {
            override val now: Duration get() = testScheduler.currentTime.milliseconds
            override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"),
            clock, backgroundScope.coroutineContext)
        val starting = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(1, transport.sentData.size)
        assertFalse(starting.isCompleted)
        val selfInfo = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x05).put(1).put(22).put(22).put(ByteArray(32) { 1 })
            .putInt(0).putInt(0).put(ByteArray(4))
            .putInt(869_525).putInt(250_000).put(11).put(5).array()
        transport.simulateReceive(Bytes(selfInfo))
        runCurrent()
        starting.await()
        assertNotNull(session.currentSelfInfo)
        return session
    }

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
