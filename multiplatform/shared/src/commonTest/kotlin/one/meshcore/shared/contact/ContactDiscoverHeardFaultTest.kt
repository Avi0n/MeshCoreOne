package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed discover lastHeard stamp is logged and the contact stamp still stands. Cancellation aborts
 * markHeard after the contact stamp. A thrown error stays logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDiscoverHeardFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledDiscoverStampAbortsAfterTheContactStamp() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = DiscoverUpsertFaultStore(stopped)
        val service = service(contacts, discovered, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.markHeard(radioID, publicKey(KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(CONTACT_TEST_NOW_SECONDS, contacts.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(discovered.list(radioID).isEmpty())
        assertTrue(logs.none { it.message.contains(DISCOVER_STAMP_FAILED) })
        assertTrue(logs.none { it.message.contains(PING_STAMP_FAILED) })
    }

    @Test
    fun aFailedDiscoverStampKeepsTheContactStamp() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = DiscoverUpsertFaultStore(DiscoverStampFailure())
        val service = service(contacts, discovered, logs)

        service.markHeard(radioID, publicKey(KEY_BYTE))

        assertEquals(CONTACT_TEST_NOW_SECONDS, contacts.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(discovered.list(radioID).isEmpty())
        val line = logs.single { it.message.contains(DISCOVER_STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(discoverStampFailedLine(FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(PING_STAMP_FAILED) })
    }

    @Test
    fun aDiscoverStampErrorStaysLogged() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val contacts = seededContacts()
        val discovered = DiscoverUpsertFaultStore(DiscoverStampProbeError())
        val service = service(contacts, discovered, logs)

        service.markHeard(radioID, publicKey(KEY_BYTE))

        assertEquals(CONTACT_TEST_NOW_SECONDS, contacts.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(discovered.list(radioID).isEmpty())
        assertEquals(
            discoverStampFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(DISCOVER_STAMP_FAILED) }.message,
        )
        assertTrue(logs.none { it.message.contains(PING_STAMP_FAILED) })
    }

    private suspend fun seededContacts(): InMemoryContactStore {
        val contacts = InMemoryContactStore()
        contacts.saveContact(
            radioID,
            contactFrame(KEY_BYTE, name = "Pinged").copy(lastAdvertTimestamp = 50, lastModified = 50),
        )
        return contacts
    }

    private fun TestScope.service(
        contacts: InMemoryContactStore,
        discovered: DiscoverUpsertFaultStore,
        logs: MutableList<DebugLogEntryDto>,
    ): ContactService = testContactService(
        store = contacts,
        discoveredNodes = discovered,
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )
}

private const val KEY_BYTE = 0x35
private const val DISCOVER_STAMP_FAILED = "Discover lastHeard stamp failed"
private const val PING_STAMP_FAILED = "Ping lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun discoverStampFailedLine(detail: String): String = "$DISCOVER_STAMP_FAILED: $detail"

private class DiscoverUpsertFaultStore(private val upsertError: Throwable) :
    DiscoveredNodeStore by InMemoryDiscoveredNodeStore() {
    override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = throw upsertError
}

private class DiscoverStampFailure : Exception("disk")

private class DiscoverStampProbeError : Error("probe")
