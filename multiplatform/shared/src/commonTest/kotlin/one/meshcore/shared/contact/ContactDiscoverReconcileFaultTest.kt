package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed discover reconcile is logged and the advert is still announced. Cancellation aborts the
 * drain loop before that announcement. A thrown error stays on the logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDiscoverReconcileFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledDiscoverReconcileAbortsBeforeTheAnnouncement() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = faultStore(stopped)
        val notifier = RecordingContactDiscoveryNotifier()
        val service = service(contacts, discovered, notifier, logs)
        val events = service.events()
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        val thrown = assertFailsWith<CancellationException> {
            service.reconcileAdvertDeltaDiscover(listOf(faultKey, laterKey), listOf(faultKey, laterKey), radioID)
        }

        assertSame(stopped, thrown)
        assertTrue(discovered.list(radioID).isEmpty())
        assertTrue(appliedKeys(events).isEmpty())
        assertTrue(notifier.notified.isEmpty())
        assertTrue(logs.none { it.message.contains(RECONCILE_UPSERT_FAILED) })
    }

    @Test
    fun aFailedDiscoverReconcileStillAnnouncesAndContinues() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = faultStore(ReconcileUpsertFailure())
        val notifier = RecordingContactDiscoveryNotifier()
        val service = service(contacts, discovered, notifier, logs)
        val events = service.events()
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        service.reconcileAdvertDeltaDiscover(listOf(faultKey, laterKey), listOf(faultKey, laterKey), radioID)

        assertTrue(discovered.list(radioID).single().publicKey.contentEquals(laterKey))
        assertEquals(listOf(faultKey, laterKey).map { it.toUppercaseHex() }, appliedKeys(events))
        assertEquals(listOf(FAULT_NAME, LATER_NAME), notifier.notified.map { it.contactName })
        val line = logs.single { it.message.contains(RECONCILE_UPSERT_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(reconcileUpsertFailedLine(faultKey, FAILURE_TEXT), line.message)
    }

    @Test
    fun aDiscoverReconcileErrorStaysLoggedAndTheRoundContinues() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = faultStore(ReconcileUpsertProbeError())
        val notifier = RecordingContactDiscoveryNotifier()
        val service = service(contacts, discovered, notifier, logs)
        val events = service.events()
        val faultKey = publicKey(FAULT_KEY_BYTE)
        val laterKey = publicKey(LATER_KEY_BYTE)

        service.reconcileAdvertDeltaDiscover(listOf(faultKey, laterKey), listOf(faultKey, laterKey), radioID)

        assertTrue(discovered.list(radioID).single().publicKey.contentEquals(laterKey))
        assertEquals(listOf(faultKey, laterKey).map { it.toUppercaseHex() }, appliedKeys(events))
        assertEquals(listOf(FAULT_NAME, LATER_NAME), notifier.notified.map { it.contactName })
        assertEquals(
            reconcileUpsertFailedLine(faultKey, PROBE_TEXT),
            logs.single { it.message.contains(RECONCILE_UPSERT_FAILED) }.message,
        )
    }

    private fun faultStore(upsertError: Throwable): ReconcileUpsertFaultStore =
        ReconcileUpsertFaultStore(InMemoryDiscoveredNodeStore(), publicKey(FAULT_KEY_BYTE), upsertError)

    private suspend fun seededContacts(): InMemoryContactStore {
        val contacts = InMemoryContactStore()
        contacts.saveContact(radioID, contactFrame(FAULT_KEY_BYTE, name = FAULT_NAME))
        contacts.saveContact(radioID, contactFrame(LATER_KEY_BYTE, name = LATER_NAME))
        return contacts
    }

    private fun TestScope.service(
        contacts: InMemoryContactStore,
        discovered: ReconcileUpsertFaultStore,
        notifier: RecordingContactDiscoveryNotifier,
        logs: MutableList<DebugLogEntryDto>,
    ): ContactService = testContactService(
        store = contacts,
        discoveredNodes = discovered,
        discoveryNotifier = notifier,
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )
}

private const val FAULT_KEY_BYTE = 0x39
private const val LATER_KEY_BYTE = 0x3A
private const val FAULT_NAME = "Reconcile"
private const val LATER_NAME = "Later"
private const val RECONCILE_UPSERT_FAILED = "Discover reconcile upsert failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun reconcileUpsertFailedLine(key: ByteArray, detail: String): String =
    "$RECONCILE_UPSERT_FAILED for ${key.toUppercaseHex()}: $detail"

private class ReconcileUpsertFaultStore(
    private val inner: InMemoryDiscoveredNodeStore,
    private val faultKey: ByteArray,
    private val upsertError: Throwable,
) : DiscoveredNodeStore by inner {
    override suspend fun upsert(node: DiscoveredNodeRecord): Boolean {
        if (node.publicKey.contentEquals(faultKey)) throw upsertError
        return inner.upsert(node)
    }
}

private class ReconcileUpsertFailure : Exception("disk")

private class ReconcileUpsertProbeError : Error("probe")
