// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventDispatcherFilteredSubscriptionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventFilterFactoryTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/MeshEventErrorCodeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.LoginInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ErrorCode
import com.meshcoreone.android.core.protocol.parser.PacketParser
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventDeliveryAcceptanceTest {
    @OriginalCase("EventDispatcherFilteredSubscriptionTests::filtered subscription receives only matching events()")
    @Test fun publicSessionFiltersBeforeCollectionAndEndsOnStop() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, clock = publicSessionClock(), coroutineContext = backgroundScope.coroutineContext)
        val stream = session.events(EventFilter.anyAcknowledgement)
        try {
            startWireSession(session, transport)
            transport.simulateReceive(advertisement(1))
            transport.simulateReceive(acknowledgement(Bytes.of(0x10, 0x20, 0x30, 0x40), 500))
            transport.simulateReceive(advertisement(2))
            runCurrent()
            session.stop()
            assertEquals(listOf(MeshEvent.Acknowledgement(Bytes.of(0x10, 0x20, 0x30, 0x40), 500u)), stream.toList())
            assertEquals("Filtering must not trigger extra commands", 1, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("EventDispatcherFilteredSubscriptionTests::filtered subscription survives flood of non-matching events()")
    @Test fun publicSessionRetainsAcknowledgementAfterFiveHundredNonmatchingEvents() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport, clock = publicSessionClock(), coroutineContext = backgroundScope.coroutineContext)
        val stream = session.events(EventFilter.anyAcknowledgement)
        try {
            startWireSession(session, transport)
            repeat(500) { transport.simulateReceive(advertisement(it % 256)) }
            transport.simulateReceive(acknowledgement(Bytes.of(0xab, 0xcd, 0xef, 0x12), 1234))
            runCurrent()
            session.stop()
            assertEquals(listOf(MeshEvent.Acknowledgement(Bytes.of(0xab, 0xcd, 0xef, 0x12), 1234u)), stream.toList())
            assertEquals("Receiving the flood must not write or refresh contacts", 1, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("EventFilterFactoryTests::anyLoginSuccess matches .loginSuccess and rejects unrelated events()")
    @OriginalCase("EventFilterFactoryTests::anyLoginFailed matches .loginFailed and rejects unrelated events()")
    @Test fun originalLoginFiltersRetainCompleteSixBytePrefixes() {
        val prefix = Bytes.of(0xde, 0xad, 0xbe, 0xef, 1, 2)
        assertTrue(EventFilter.anyLoginSuccess.matches(MeshEvent.LoginSuccess(LoginInfo(1u, false, prefix))))
        assertFalse(EventFilter.anyLoginSuccess.matches(MeshEvent.LoginFailed(null)))
        assertFalse(EventFilter.anyLoginSuccess.matches(MeshEvent.Ok(null)))
        assertTrue(EventFilter.anyLoginFailed.matches(MeshEvent.LoginFailed(Bytes.of(0xaa, 0xbb))))
        assertTrue(EventFilter.anyLoginFailed.matches(MeshEvent.LoginFailed(null)))
        assertFalse(EventFilter.anyLoginFailed.matches(MeshEvent.LoginSuccess(LoginInfo(0u, false, prefix))))
        assertFalse(EventFilter.anyLoginFailed.matches(MeshEvent.Ok(null)))
    }

    @OriginalCase("MeshEventErrorCodeTests::PacketParser maps a RESP_CODE_ERR frame to .error with the firmware sub-code()")
    @Test fun originalErrorFrameReachesTheTypedFirmwareAccessor() {
        val parsed = PacketParser.parse(Bytes.of(1, 6))
        assertTrue(parsed is MeshEvent.Error)
        assertEquals(ErrorCode.ILLEGAL_ARGUMENT, parsed.errorCode)
        assertEquals(6u.toUByte(), (parsed as MeshEvent.Error).code)
    }

    private suspend fun TestScope.startWireSession(session: MeshCoreSession, transport: MockTransport) {
        val startup = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(
            Bytes.of(1, 3, 32, 32, 32, 32, 32, 32, 0x4d, 0x65, 0x73, 0x68, 0x43),
            transport.sentData.single(),
        )
        val selfInfo = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN)
            .put(5).put(1).put(20).put(30).put(ByteArray(32) { 0xaa.toByte() })
            .putInt(0).putInt(0).put(2).put(1).put(0).put(1)
            .putInt(915_000).putInt(250_000).put(10).put(5).array()
        transport.simulateReceive(Bytes(selfInfo))
        runCurrent()
        startup.await()
    }

    private fun advertisement(firstByte: Int): Bytes =
        Bytes.of(0x80, firstByte) + Bytes(ByteArray(31))

    private fun acknowledgement(code: Bytes, tripTime: Int): Bytes =
        Bytes.of(0x82) + code + Bytes(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(tripTime).array(),
        )
}
