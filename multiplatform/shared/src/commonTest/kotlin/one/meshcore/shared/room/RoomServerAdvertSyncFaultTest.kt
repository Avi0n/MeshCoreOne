package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An advert-path history sync reports a failed path and keeps going. Cancellation keeps the thrown
 * instance and does not record the advert path as failed. An error is recorded the same way and does
 * not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerAdvertSyncFaultTest {
    @Test
    fun aCancelledAdvertSyncKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> { harness.service.syncHistoryIfPossible(SESSION_ID) }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains("Advert path failed") })
    }

    @Test
    fun anAdvertSyncFailureRecordsTheFailedPathAndContinues() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomAdvertSyncFailure())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == advertPathFailedLine("Test Room", "radio") })
    }

    @Test
    fun anAdvertSyncErrorIsRecordedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomAdvertSyncProbeError())

        harness.service.syncHistoryIfPossible(SESSION_ID)

        assertTrue(harness.logs.any { it.message == advertPathFailedLine("Test Room", "probe") })
    }

    private suspend fun TestScope.harness(syncFailure: Throwable): AdvertSyncHarness {
        val store = InMemoryRoomStore()
        val sessionRow = roomSession(id = SESSION_ID)
        store.seedSession(sessionRow)
        store.setContactRouting(sessionRow.radioID, sessionRow.publicKey, isFloodRouted = false)
        val room = FakeRoomServerSession()
        room.historySyncError = syncFailure
        val passwords = InMemoryNodePasswordStore()
        val logs = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "RoomServerService", record = { logs += it })
        val service = RoomServerService(
            remoteNodeService = RemoteNodeService(FakeNodeSession(), store, passwords, backgroundScope),
            store = store,
            roomSession = room,
            passwordStore = passwords,
            scope = backgroundScope,
            edges = RoomServerEdges(logger = logger),
        )
        return AdvertSyncHarness(service, logs)
    }
}

private class AdvertSyncHarness(val service: RoomServerService, val logs: List<DebugLogEntryDto>)

private class RoomAdvertSyncFailure : Exception("radio")

private class RoomAdvertSyncProbeError : Error("probe")

private val SESSION_ID: Uuid = testUuid(1)
