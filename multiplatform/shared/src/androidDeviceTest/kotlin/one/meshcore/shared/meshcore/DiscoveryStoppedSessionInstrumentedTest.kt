package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.tools.NodeDiscoveryFilter
import one.meshcore.shared.tools.isStoppedDiscoverySession
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryStoppedSessionInstrumentedTest {
    @Test
    fun stoppingAPendingNativeDiscoveryReportsAStoppedSessionFailure() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val command = async(Dispatchers.Default) {
                runCatching {
                    session.sendNodeDiscoverRequest(
                        filter = NodeDiscoveryFilter.REPEATERS.filterValue,
                        prefixOnly = false,
                    )
                }
            }
            withTimeout(TIMEOUT_MS) {
                var frame = transport.awaitSend()
                while (frame.firstOrNull() != DISCOVER_CODE) frame = transport.awaitSend()
            }
            session.stop()
            val failure = withTimeout(TIMEOUT_MS) { command.await().exceptionOrNull() }
            assertTrue(
                "Unexpected pending-stop failure: $failure",
                failure != null && isStoppedDiscoverySession(failure),
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val DISCOVER_CODE: Byte = 0x37
    }
}
