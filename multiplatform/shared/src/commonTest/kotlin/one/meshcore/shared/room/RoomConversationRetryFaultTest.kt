package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A room retry reports the mapped failure and keeps the existing row. Cancellation keeps the thrown
 * instance and the row. An error is reported the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationRetryFaultTest {
    @Test
    fun aCancelledRetryKeepsTheThrownInstanceAndTheRow() = runTest {
        val stopped = CancellationException("stopped")
        val harness = harness()
        harness.store.failure = stopped

        val thrown = assertFailsWith<CancellationException> {
            harness.controller.retryMessage(harness.message.id)
        }

        assertSame(stopped, thrown)
        assertEquals(listOf(harness.message.id), harness.controller.messages.map { it.id })
        assertNull(harness.controller.errorMessage)
    }

    @Test
    fun aRetryFailureReportsTheMappedTextAndKeepsTheRow() = runTest {
        val harness = harness()
        harness.store.failure = RoomRetryFailure()

        harness.controller.retryMessage(harness.message.id)

        assertEquals("room busy", harness.controller.errorMessage)
        assertEquals(listOf(harness.message.id), harness.controller.messages.map { it.id })
    }

    @Test
    fun aRetryErrorIsReportedAndDoesNotEscape() = runTest {
        val harness = harness()
        harness.store.failure = RoomRetryProbeError()

        harness.controller.retryMessage(harness.message.id)

        assertEquals("send fail", harness.controller.errorMessage)
        assertEquals(listOf(harness.message.id), harness.controller.messages.map { it.id })
    }

    private suspend fun TestScope.harness(): RetryHarness {
        val inner = InMemoryRoomStore()
        val store = RoomRetryFaultStore(inner)
        val message = roomMessage(id = testUuid(MESSAGE_ID))
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
                errorText = { error -> if (error is RoomRetryFailure) "room busy" else null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
            ),
        )
        controller.appendMessageIfNew(message)
        return RetryHarness(store, controller, message)
    }
}

private class RetryHarness(
    val store: RoomRetryFaultStore,
    val controller: RoomConversationController,
    val message: RoomMessageDto,
)

private class RoomRetryFailure : Exception("radio")

private class RoomRetryProbeError : Error("probe")

private class RoomRetryFaultStore(private val backing: InMemoryRoomStore) : RoomStore by backing {
    var failure: Throwable? = null

    override suspend fun fetchRoomMessage(id: Uuid): RoomMessageDto? {
        failure?.let { throw it }
        return backing.fetchRoomMessage(id)
    }
}

private const val MESSAGE_ID = 3
private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
