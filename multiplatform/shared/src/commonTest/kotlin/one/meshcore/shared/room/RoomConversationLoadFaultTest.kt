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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A room load reports the mapped failure and keeps the previous messages. Cancellation keeps the
 * thrown instance and leaves the spinner up. An error is reported the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationLoadFaultTest {
    @Test
    fun aCancelledLoadKeepsTheThrownInstanceAndTheMessages() = runTest {
        val stopped = CancellationException("stopped")
        val harness = harness(loadFailedMessage = { "load fail" })
        harness.controller.loadMessages(roomSession())
        harness.store.failure = stopped

        val thrown = assertFailsWith<CancellationException> {
            harness.controller.loadMessages(roomSession())
        }

        assertSame(stopped, thrown)
        assertEquals(listOf("hello"), harness.controller.messages.map { it.text })
        assertNull(harness.controller.errorMessage)
        assertTrue(harness.controller.isLoading)
        assertTrue(harness.controller.hasLoadedOnce)
    }

    @Test
    fun aLoadFailureReportsTheMappedTextAndKeepsTheMessages() = runTest {
        val harness = harness(loadFailedMessage = { "load fail" })
        harness.controller.loadMessages(roomSession())
        harness.store.failure = RoomLoadFailure()

        harness.controller.loadMessages(roomSession())

        assertEquals("room unavailable", harness.controller.errorMessage)
        assertEquals(listOf("hello"), harness.controller.messages.map { it.text })
        assertFalse(harness.controller.isLoading)
        assertTrue(harness.controller.hasLoadedOnce)
    }

    @Test
    fun aLoadErrorIsReportedAndDoesNotEscape() = runTest {
        val harness = harness(loadFailedMessage = { "load fail" })
        harness.controller.loadMessages(roomSession())
        harness.store.failure = RoomLoadProbeError()

        harness.controller.loadMessages(roomSession())

        assertEquals("load fail", harness.controller.errorMessage)
        assertEquals(listOf("hello"), harness.controller.messages.map { it.text })
        assertFalse(harness.controller.isLoading)
        assertTrue(harness.controller.hasLoadedOnce)
    }

    @Test
    fun aLoadFailureUsesTheCurrentLocalizedFallback() = runTest {
        var fallback = "load fail"
        val harness = harness(loadFailedMessage = { fallback })
        harness.controller.loadMessages(roomSession())
        harness.store.failure = RoomLoadProbeError()
        fallback = "translated load fail"

        harness.controller.loadMessages(roomSession())

        assertEquals("translated load fail", harness.controller.errorMessage)
    }

    private suspend fun TestScope.harness(loadFailedMessage: () -> String): LoadHarness {
        val inner = InMemoryRoomStore()
        val store = RoomLoadFaultStore(inner)
        val session = roomSession()
        inner.saveRoomMessage(roomMessage(id = testUuid(MESSAGE_ID), sessionID = session.id))
        val passwords = InMemoryNodePasswordStore()
        return LoadHarness(
            store = store,
            controller = RoomConversationController(
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
                    loadFailedMessage = loadFailedMessage,
                    errorText = { error -> if (error is RoomLoadFailure) "room unavailable" else null },
                    sendFailedMessage = { ROOM_TEST_SEND_ERROR },
                ),
            ),
        )
    }
}

private class LoadHarness(val store: RoomLoadFaultStore, val controller: RoomConversationController)

private class RoomLoadFailure : Exception("radio")

private class RoomLoadProbeError : Error("probe")

private class RoomLoadFaultStore(private val backing: InMemoryRoomStore) : RoomStore by backing {
    var failure: Throwable? = null

    override suspend fun fetchRoomMessages(sessionID: Uuid, limit: Int?, offset: Int?): List<RoomMessageDto> {
        failure?.let { throw it }
        return backing.fetchRoomMessages(sessionID, limit, offset)
    }
}

private const val MESSAGE_ID = 2
private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
