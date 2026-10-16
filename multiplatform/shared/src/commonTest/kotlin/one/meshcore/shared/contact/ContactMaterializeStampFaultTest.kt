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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed pending-advert lastHeard stamp is logged and the saved contact still returns.
 * Cancellation aborts before the contact list refreshes. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactMaterializeStampFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledMaterializeStampAbortsBeforeTheContactRefresh() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeStampFaultStore(InMemoryContactStore(), stopped)
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        val thrown = assertFailsWith<CancellationException> {
            service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID)
        }

        assertSame(stopped, thrown)
        val saved = store.fetchContact(radioID, key)
        assertNotNull(saved)
        assertNull(saved.lastHeardTimestamp)
        assertEquals(0, contactChanges)
        assertTrue(appliedKeys(events).isEmpty())
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
        assertTrue(logs.none { it.message.contains(MATERIALIZE_FAILED) })
    }

    @Test
    fun aFailedMaterializeStampStillReturnsTheSavedContact() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeStampFaultStore(InMemoryContactStore(), MaterializeStampFailure())
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        val contact = service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID)

        assertNotNull(contact)
        assertEquals(CONTACT_NAME, contact.name)
        assertNull(contact.lastHeardTimestamp)
        assertEquals(1, contactChanges)
        assertEquals(listOf(key.toUppercaseHex()), appliedKeys(events))
        val line = logs.single { it.message.contains(STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(stampFailedLine(key, FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(MATERIALIZE_FAILED) })
    }

    @Test
    fun aMaterializeStampErrorStaysLoggedAndTheContactReturns() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val key = publicKey(KEY_BYTE)
        val store = MaterializeStampFaultStore(InMemoryContactStore(), MaterializeStampProbeError())
        val service = loggedPendingContact(store, logs, KEY_BYTE, CONTACT_NAME) { contactChanges += 1 }
        val events = service.events()
        queueSyncedAdvert(service, radioID, key)

        val contact = service.materializeContactForPendingAdvert(key.copyOf(PREFIX_LENGTH), radioID)

        assertNotNull(contact)
        assertNull(contact.lastHeardTimestamp)
        assertEquals(1, contactChanges)
        assertEquals(listOf(key.toUppercaseHex()), appliedKeys(events))
        assertEquals(stampFailedLine(key, PROBE_TEXT), logs.single { it.message.contains(STAMP_FAILED) }.message)
        assertTrue(logs.none { it.message.contains(MATERIALIZE_FAILED) })
    }
}

private const val KEY_BYTE = 0x3C
private const val PREFIX_LENGTH = 6
private const val CONTACT_NAME = "Pending"
private const val STAMP_FAILED = "materialize pending advert lastHeard stamp failed"
private const val MATERIALIZE_FAILED = "materialize pending advert failed key="
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun stampFailedLine(key: ByteArray, detail: String): String =
    "$STAMP_FAILED key=${key.toUppercaseHex()}: $detail"

private class MaterializeStampFaultStore(private val inner: InMemoryContactStore, private val touchError: Throwable) :
    ContactStore by inner {
    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean =
        throw touchError
}

private class MaterializeStampFailure : Exception("disk")

private class MaterializeStampProbeError : Error("probe")
