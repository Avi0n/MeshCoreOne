package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed full-sync prune is logged and later orphans still delete. Cancellation aborts the prune
 * loop. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactFullSyncPruneFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledFullSyncPruneAbortsTheLoop() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(stopped)
        val service = loggedContactService(seeded.store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.pruneOrphans(radioID, emptySet(), reportedTotal = 0)
        }

        assertSame(stopped, thrown)
        assertNotNull(seeded.store.fetchContact(radioID, publicKey(FAULT_KEY_BYTE)))
        assertNotNull(seeded.store.fetchContact(radioID, publicKey(LATER_KEY_BYTE)))
        assertTrue(logs.none { it.message.contains(PRUNE_FAILED) })
    }

    @Test
    fun aFailedFullSyncPruneStillDeletesLaterOrphans() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(FullSyncPruneFailure())
        val service = loggedContactService(seeded.store, logs)

        service.pruneOrphans(radioID, emptySet(), reportedTotal = 0)

        assertNotNull(seeded.store.fetchContact(radioID, publicKey(FAULT_KEY_BYTE)))
        assertNull(seeded.store.fetchContact(radioID, publicKey(LATER_KEY_BYTE)))
        val line = logs.single { it.message.contains(PRUNE_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(pruneFailedLine(seeded.faultId, FAILURE_TEXT), line.message)
    }

    @Test
    fun aFullSyncPruneErrorStaysLoggedAndLaterOrphansDelete() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(FullSyncPruneProbeError())
        val service = loggedContactService(seeded.store, logs)

        service.pruneOrphans(radioID, emptySet(), reportedTotal = 0)

        assertNotNull(seeded.store.fetchContact(radioID, publicKey(FAULT_KEY_BYTE)))
        assertNull(seeded.store.fetchContact(radioID, publicKey(LATER_KEY_BYTE)))
        assertEquals(
            pruneFailedLine(seeded.faultId, PROBE_TEXT),
            logs.single { it.message.contains(PRUNE_FAILED) }.message,
        )
    }

    private suspend fun seededStore(deleteError: Throwable): SeededPruneStore {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(FAULT_KEY_BYTE, name = FAULT_NAME))
        inner.saveContact(radioID, contactFrame(LATER_KEY_BYTE, name = LATER_NAME))
        val faultId = inner.fetchContact(radioID, publicKey(FAULT_KEY_BYTE))!!.id
        return SeededPruneStore(DeleteContactFaultStore(inner, faultId, deleteError), faultId)
    }
}

private const val FAULT_KEY_BYTE = 0x3E
private const val LATER_KEY_BYTE = 0x3F
private const val FAULT_NAME = "Aaa"
private const val LATER_NAME = "Zzz"
private const val PRUNE_FAILED = "Full sync prune failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun pruneFailedLine(id: Uuid, detail: String): String = "$PRUNE_FAILED for $id: $detail"

private data class SeededPruneStore(val store: DeleteContactFaultStore, val faultId: Uuid)

private class FullSyncPruneFailure : Exception("disk")

private class FullSyncPruneProbeError : Error("probe")
