package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed pending-advert save is logged and materialize returns null. Cancellation aborts before
 * the contact list refreshes. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactMaterializeSaveFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledMaterializeSaveAbortsBeforeTheContactRefresh() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeSaveFaultStore(stopped)
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        val thrown = assertFailsWith<CancellationException> {
            service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID)
        }

        assertSame(stopped, thrown)
        assertNull(store.fetchContact(radioID, key))
        assertEquals(0, contactChanges)
        assertTrue(appliedKeys(events).isEmpty())
        assertTrue(logs.none { it.message.contains(MATERIALIZE_FAILED) })
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
    }

    @Test
    fun aFailedMaterializeSaveReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeSaveFaultStore(MaterializeSaveFailure())
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        assertNull(service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID))

        assertNull(store.fetchContact(radioID, key))
        assertEquals(0, contactChanges)
        assertTrue(appliedKeys(events).isEmpty())
        val line = logs.single { it.message.contains(MATERIALIZE_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(materializeFailedLine(key, FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
    }

    @Test
    fun aMaterializeSaveErrorStaysLoggedAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeSaveFaultStore(MaterializeSaveProbeError())
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        assertNull(service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID))

        assertNull(store.fetchContact(radioID, key))
        assertEquals(0, contactChanges)
        assertTrue(appliedKeys(events).isEmpty())
        assertEquals(
            materializeFailedLine(key, PROBE_TEXT),
            logs.single { it.message.contains(MATERIALIZE_FAILED) }.message,
        )
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
    }
}

private const val KEY_BYTE = 0x3D
private const val PREFIX_LENGTH = 6
private const val CONTACT_NAME = "Pending"
private const val MATERIALIZE_FAILED = "materialize pending advert failed"
private const val STAMP_FAILED = "materialize pending advert lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun materializeFailedLine(key: ByteArray, detail: String): String =
    "$MATERIALIZE_FAILED key=${key.toUppercaseHex()}: $detail"

private class MaterializeSaveFaultStore(private val saveError: Throwable) : ContactStore by InMemoryContactStore() {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = throw saveError
}

private class MaterializeSaveFailure : Exception("disk")

private class MaterializeSaveProbeError : Error("probe")
