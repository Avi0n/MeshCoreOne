package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A room send reports the mapped failure and posts nothing. Cancellation keeps the thrown instance
 * and leaves the spinner up. An error is reported the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationSendFaultTest {
    @Test
    fun aCancelledSendKeepsTheThrownInstanceAndTheSpinner() = runTest {
        val stopped = CancellationException("stopped")
        val harness = harness()
        harness.store.failure = stopped

        val thrown = assertFailsWith<CancellationException> {
            harness.controller.sendMessage("hi room")
        }

        assertSame(stopped, thrown)
        assertTrue(harness.controller.messages.isEmpty())
        assertNull(harness.controller.errorMessage)
        assertTrue(harness.controller.isSending)
    }

    @Test
    fun aSendFailureReportsTheMappedTextAndClearsTheSpinner() = runTest {
        val harness = harness()
        harness.store.failure = RoomSendFailure()

        harness.controller.sendMessage("hi room")

        assertEquals("room busy", harness.controller.errorMessage)
        assertTrue(harness.controller.messages.isEmpty())
        assertFalse(harness.controller.isSending)
    }

    @Test
    fun aSendErrorIsReportedAndDoesNotEscape() = runTest {
        val harness = harness()
        harness.store.failure = RoomSendProbeError()

        harness.controller.sendMessage("hi room")

        assertEquals("send fail", harness.controller.errorMessage)
        assertTrue(harness.controller.messages.isEmpty())
        assertFalse(harness.controller.isSending)
    }

    private suspend fun TestScope.harness(): SendHarness {
        val inner = InMemoryRoomStore()
        val store = RoomSendFaultStore(inner)
        val session = roomSession()
        inner.seedSession(session)
        val passwords = InMemoryNodePasswordStore()
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = {
                    RoomServerService(
                        remoteNodeService = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope),
                        store = store,
                        roomSession = FakeRoomServerSession(),
                        passwordStore = passwords,
                        scope = backgroundScope,
                        edges = RoomServerEdges(now = { STORED_AT_EPOCH_SECONDS }),
                    )
                },
                scope = backgroundScope,
                sendFailedMessage = { "send fail" },
                errorText = { error -> if (error is RoomSendFailure) "room busy" else null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
            ),
        )
        controller.loadMessages(session)
        return SendHarness(store, controller)
    }
}

private class SendHarness(val store: RoomSendFaultStore, val controller: RoomConversationController)

private class RoomSendFailure : Exception("radio")

private class RoomSendProbeError : Error("probe")

private class RoomSendFaultStore(private val backing: InMemoryRoomStore) : RoomStore by backing {
    var failure: Throwable? = null

    override suspend fun saveRoomMessage(dto: RoomMessageDto) {
        failure?.let { throw it }
        backing.saveRoomMessage(dto)
    }
}

private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
