// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/ConnectionStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/MeshCoreSessionStopTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleAcceptanceTest {
    @OriginalCase("ConnectionStateTests::initial start emits connecting then connected()")
    @OriginalCase("ConnectionStateTests::reconnect start emits reconnecting then connected()")
    @Test fun originalStartupStatesRetainTheExactHandshakeInputs() = runTest {
        for (attempt in listOf<Long?>(null, 1)) {
            val transport = MockTransport()
            val session = MeshCoreSession(transport,
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"),
                publicSessionClock(), backgroundScope.coroutineContext)
            val states = mutableListOf<ConnectionState>()
            val observer = backgroundScope.launch { session.connectionState.collect { states += it } }
            runCurrent()
            assertEquals(listOf(ConnectionState.Disconnected), states)
            try {
                val startup = backgroundScope.async { session.start(reconnectingAttempt = attempt) }
                runCurrent()
                assertEquals(listOf(Bytes.fromHex("010320202020202054657374")), transport.sentData)
                assertFalse(startup.isCompleted)
                assertNull(session.currentSelfInfo)
                val pendingState = if (attempt == null) ConnectionState.Connecting else ConnectionState.Reconnecting(1)
                assertEquals(listOf(ConnectionState.Disconnected, pendingState, ConnectionState.Connected), states)
                transport.simulateReceive(Bytes.fromHex(
                    "05000000" + "01".repeat(32) + "00".repeat(12) +
                        "38630d0048e801000705546573744e6f6465"))
                runCurrent()
                startup.await()
                assertEquals(listOf(ConnectionState.Disconnected, pendingState, ConnectionState.Connected), states)
                assertEquals("TestNode", session.currentSelfInfo?.name)
                assertEquals(Bytes(ByteArray(32) { 1 }), session.currentSelfInfo?.publicKey)
                assertTrue(transport.isConnected())
            } finally {
                session.stop()
            }
            runCurrent()
            assertEquals(ConnectionState.Disconnected, states.last())
            assertTrue(observer.isCompleted)
            assertFalse(transport.isConnected())
        }
    }

    @OriginalCase("ConnectionStateTests::failed appStart unwinds start so it can be retried()")
    @Test fun originalFiftyMillisecondFailureKeepsIntermediateStatesAndWritesAgain() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = 0.05, clientIdentifier = "Test"),
            publicSessionClock(), backgroundScope.coroutineContext)
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch { session.connectionState.collect { states += it } }
        runCurrent()
        assertEquals(listOf(ConnectionState.Disconnected), states)
        try {
            repeat(2) { attempt ->
                val startup = backgroundScope.async {
                    try {
                        session.start()
                        error("A missing selfInfo cannot make startup successful")
                    } catch (failure: MeshCoreException.Timeout) {
                        failure
                    }
                }
                runCurrent()
                assertEquals(attempt + 1, transport.sentData.size)
                assertEquals(Bytes.fromHex("010320202020202054657374"), transport.sentData.last())
                assertEquals(ConnectionState.Connecting, states[states.lastIndex - 1])
                assertEquals(ConnectionState.Connected, states.last())
                assertFalse(startup.isCompleted)
                advanceTimeBy(49)
                runCurrent()
                assertFalse("The original fifty-millisecond deadline has not expired", startup.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(startup.await() is MeshCoreException.Timeout)
                assertTrue(states.last() is ConnectionState.Failed)
                assertNull(session.currentSelfInfo)
                assertFalse(transport.isConnected())
            }
            assertEquals(2, transport.sentData.size)
            assertEquals(2, states.count { it == ConnectionState.Connecting })
            assertEquals(2, states.count { it == ConnectionState.Connected })
            assertEquals(2, states.count { it is ConnectionState.Failed })
        } finally {
            session.stop()
        }
    }

    @OriginalCase("ConnectionStateTests::getContact rejects short public key before sending()")
    @OriginalCase("ConnectionStateTests::requestStatus rejects short public key before sending()")
    @OriginalCase("ConnectionStateTests::setPathHashMode rejects reserved mode before sending()")
    @Test fun originalInvalidInputsDoNotWriteOrConnect() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"),
            publicSessionClock(), backgroundScope.coroutineContext)
        try {
            for (operation in listOf<suspend () -> Unit>(
                { session.getContact(Bytes(ByteArray(31) { 0xAA.toByte() })); Unit },
                { session.requestStatus(Bytes(ByteArray(31) { 0xBB.toByte() })); Unit },
                { session.setPathHashMode(3u) },
            )) {
                try {
                    operation()
                    fail("Invalid source input must remain a typed failure")
                } catch (_: MeshCoreException.InvalidInput) {
                    assertTrue(transport.sentData.isEmpty())
                    assertFalse(transport.isConnected())
                }
            }
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionStopTests::stop with disconnectTransport false leaves the transport connected()")
    @OriginalCase("MeshCoreSessionStopTests::stop by default disconnects the transport()")
    @Test fun originalUnstartedStopRetainsTheDisconnectOwnershipFlag() = runTest {
        for (disconnect in listOf(false, true)) {
            val transport = MockTransport()
            var disconnects = 0
            val counted = object : MeshTransport by transport {
                override suspend fun disconnect() {
                    disconnects += 1
                    transport.disconnect()
                }
            }
            val session = MeshCoreSession(counted, clock = publicSessionClock(),
                coroutineContext = backgroundScope.coroutineContext)
            assertFalse(transport.isConnected())
            if (disconnect) session.stop() else session.stop(disconnectTransport = false)
            assertEquals(if (disconnect) 1 else 0, disconnects)
            assertTrue(transport.sentData.isEmpty())
            assertFalse(transport.isConnected())
        }
    }
}
