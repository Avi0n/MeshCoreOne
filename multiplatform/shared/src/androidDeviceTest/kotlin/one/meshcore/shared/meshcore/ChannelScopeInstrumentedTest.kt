package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber")
class ChannelScopeInstrumentedTest {
    @Test
    fun scopeAndMessageCannotBeSeparatedByAnotherCommand() = runBlocking {
        val cases = listOf(
            ResolvedFloodScope.Scope(FloodScope.Region("Europe")) to
                (byteArrayOf(0x36, 0) + MeshCoreSessionInstrumentedTest.sha256("#Europe").copyOf(16)),
            ResolvedFloodScope.Scope(FloodScope.Disabled) to (byteArrayOf(0x36, 0) + ByteArray(16)),
            ResolvedFloodScope.Unscoped to byteArrayOf(0x36, 1),
        )
        for ((scope, expected) in cases) {
            val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
            MeshCoreSession(transport).use { session ->
                MeshCoreSessionInstrumentedTest.connect(session, transport)
                val sent = async(Dispatchers.Default) { session.sendChannelMessage(1, "Scoped", TIMESTAMP, scope) }
                assertArrayEquals(expected, withTimeout(TIMEOUT_MS) { transport.awaitSend() })
                val other = async(Dispatchers.Default) { session.setTime(TIMESTAMP) }
                session.deliverReceived(OK)
                val message = withTimeout(TIMEOUT_MS) { transport.awaitSend() }
                assertEquals(0x03.toByte(), message.first())
                assertEquals(1.toByte(), message[2])
                assertEquals("Scoped", message.copyOfRange(7, message.size).decodeToString())
                session.deliverReceived(OK)
                withTimeout(TIMEOUT_MS) { sent.await() }
                assertEquals(0x06.toByte(), withTimeout(TIMEOUT_MS) { transport.awaitSend() }.first())
                session.deliverReceived(OK)
                withTimeout(TIMEOUT_MS) { other.await() }
            }
        }
    }

    @Test
    fun rejectedScopeNeverWritesTheMessage() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val sent = async(Dispatchers.Default) {
                runCatching { session.sendChannelMessage(1, "Rejected", TIMESTAMP, ResolvedFloodScope.Unscoped) }
            }
            assertArrayEquals(byteArrayOf(0x36, 1), withTimeout(TIMEOUT_MS) { transport.awaitSend() })
            session.deliverReceived(byteArrayOf(0x01, 0x01))
            val failure = withTimeout(TIMEOUT_MS) { sent.await().exceptionOrNull() }
            assertTrue(failure is MeshBridgeException)
            assertEquals(BridgeErrorCode.DEVICE_ERROR, (failure as MeshBridgeException).code)
            val next = async(Dispatchers.Default) { session.setTime(TIMESTAMP) }
            assertEquals(0x06.toByte(), withTimeout(TIMEOUT_MS) { transport.awaitSend() }.first())
            session.deliverReceived(OK)
            withTimeout(TIMEOUT_MS) { next.await() }
        }
    }

    @Test
    fun cancelledQueuedOrAwaitingScopeSendsDrainWithoutTransmitting() = runBlocking {
        for (scopeWasSent in listOf(true, false)) {
            val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
            MeshCoreSession(transport).use { session ->
                MeshCoreSessionInstrumentedTest.connect(session, transport)
                val blocker = if (!scopeWasSent) async(Dispatchers.Default) { session.setTime(TIMESTAMP) } else null
                if (blocker != null) {
                    assertEquals(0x06.toByte(), withTimeout(TIMEOUT_MS) { transport.awaitSend() }.first())
                }
                val sent = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    session.sendChannelMessage(1, "Cancelled", TIMESTAMP, ResolvedFloodScope.Unscoped)
                }
                if (scopeWasSent) {
                    assertArrayEquals(byteArrayOf(0x36, 1), withTimeout(TIMEOUT_MS) { transport.awaitSend() })
                }
                sent.cancelAndJoin()
                val next = async(Dispatchers.Default) { session.setTime(TIMESTAMP) }
                session.deliverReceived(OK)
                assertEquals(0x06.toByte(), withTimeout(TIMEOUT_MS) { transport.awaitSend() }.first())
                session.deliverReceived(OK)
                withTimeout(TIMEOUT_MS) {
                    next.await()
                    blocker?.await()
                }
            }
        }
    }

    @Test
    fun closeCancelsPendingSendsAndIgnoresLateTransportSignals() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        val session = MeshCoreSession(transport)
        MeshCoreSessionInstrumentedTest.connect(session, transport)
        val sent = async(Dispatchers.Default) {
            session.sendChannelMessage(1, "Cancelled", TIMESTAMP, ResolvedFloodScope.Unscoped)
        }
        assertArrayEquals(byteArrayOf(0x36, 1), withTimeout(TIMEOUT_MS) { transport.awaitSend() })
        session.close()
        withTimeout(TIMEOUT_MS) { sent.join() }
        assertTrue(sent.isCancelled)
        session.deliverReceived(OK)
        session.reportDisconnected()
        session.close()
    }

    private companion object {
        const val TIMESTAMP = 1_700_000_000L
        const val TIMEOUT_MS = 5_000L
        val OK = byteArrayOf(0)
    }
}
