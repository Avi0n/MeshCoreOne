package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.model.RoomPermissionLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliPollCancellationTest {
    @Test
    fun cancelledGetMessageReleasesTheRequestAndSlotWithoutWaitingForTimeout() = runTest {
        val cancelled = CancellationException("GET_MSG stopped")
        val radio = FakeNodeSession()
        var cancelRead = true
        val session = object : NodeSession by radio {
            override suspend fun getMessage(): GetMessageResult {
                if (cancelRead) throw cancelled
                return radio.getMessage()
            }
        }
        val store = InMemoryRemoteNodeStore()
        val node = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(node)
        store.setContactRouting(node.radioID, node.publicKey, isFloodRouted = true)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: CancellationException? = null
        val first = launch {
            try {
                cli.sendRawCLICommand(node.id, "first")
            } catch (error: CancellationException) {
                thrown = error
            }
        }
        runCurrent()
        assertTrue(first.isCompleted, "A cancelled GET_MSG must resume its CLI waiter immediately")
        assertSame(cancelled, thrown?.cause ?: thrown)
        assertTrue(cli.pendingCLIRequests.isEmpty())
        assertEquals(0, testScheduler.currentTime)

        cancelRead = false
        var reply: String? = null
        val second = launch { reply = cli.sendRawCLICommand(node.id, "second") }
        runCurrent()
        assertEquals(2, radio.sendCommandCalls.size)
        assertFalse(second.isCompleted)
        radio.emitCliResponse("01|next reply")
        runCurrent()
        assertEquals("next reply", reply)
        assertTrue(second.isCompleted)
        assertTrue(cli.pendingCLIRequests.isEmpty())
    }
}
