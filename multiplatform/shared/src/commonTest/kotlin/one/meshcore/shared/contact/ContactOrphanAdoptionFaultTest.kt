package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed orphan adoption is logged and the delta round does not announce it. Cancellation aborts
 * before the conversation refresh. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactOrphanAdoptionFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledOrphanAdoptionAbortsBeforeTheConversationRefresh() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        var conversationChanges = 0
        val adopter = OrphanAdoptionFaultAdopter(stopped)
        val contacts = seededContacts()
        val service = service(contacts, adopter, logs) { conversationChanges += 1 }
        val events = service.events()

        val thrown = assertFailsWith<CancellationException> {
            service.adoptOrphanedMessages(listOf(publicKey(KEY_BYTE)), radioID)
        }

        assertSame(stopped, thrown)
        assertEquals(1, adopter.calls)
        assertEquals(0, conversationChanges)
        assertTrue(adoptedEvents(events).isEmpty())
        assertTrue(logs.none { it.message.contains(ADOPTION_FAILED) })
        assertTrue(logs.none { it.message.contains(ADOPTED_INFO) })
    }

    @Test
    fun aFailedOrphanAdoptionDoesNotAnnounce() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var conversationChanges = 0
        val adopter = OrphanAdoptionFaultAdopter(OrphanAdoptionFailure())
        val contacts = seededContacts()
        val service = service(contacts, adopter, logs) { conversationChanges += 1 }
        val events = service.events()

        service.adoptOrphanedMessages(listOf(publicKey(KEY_BYTE)), radioID)

        assertEquals(1, adopter.calls)
        assertEquals(0, conversationChanges)
        assertTrue(adoptedEvents(events).isEmpty())
        val line = logs.single { it.message.contains(ADOPTION_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(adoptionFailedLine(FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(ADOPTED_INFO) })
    }

    @Test
    fun anOrphanAdoptionErrorStaysLoggedAndDoesNotAnnounce() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var conversationChanges = 0
        val adopter = OrphanAdoptionFaultAdopter(OrphanAdoptionProbeError())
        val contacts = seededContacts()
        val service = service(contacts, adopter, logs) { conversationChanges += 1 }
        val events = service.events()

        service.adoptOrphanedMessages(listOf(publicKey(KEY_BYTE)), radioID)

        assertEquals(1, adopter.calls)
        assertEquals(0, conversationChanges)
        assertTrue(adoptedEvents(events).isEmpty())
        assertEquals(
            adoptionFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(ADOPTION_FAILED) }.message,
        )
        assertTrue(logs.none { it.message.contains(ADOPTED_INFO) })
    }

    private suspend fun seededContacts(): InMemoryContactStore {
        val contacts = InMemoryContactStore()
        contacts.saveContact(radioID, contactFrame(KEY_BYTE, name = "Orphan"))
        return contacts
    }

    private fun TestScope.service(
        contacts: InMemoryContactStore,
        adopter: OrphanAdoptionFaultAdopter,
        logs: MutableList<DebugLogEntryDto>,
        onConversationsChanged: suspend () -> Unit,
    ): ContactService = testContactService(
        store = contacts,
        orphanDmAdopter = adopter,
        syncCoordinator = CallbackContactSyncCoordinator(onConversationsChanged = onConversationsChanged),
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )
}

private const val KEY_BYTE = 0x3B
private const val ADOPTION_FAILED = "Orphaned DM adoption failed"
private const val ADOPTED_INFO = "Adopted orphaned DMs"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun adoptionFailedLine(detail: String): String = "$ADOPTION_FAILED: $detail"

private suspend fun adoptedEvents(events: ReceiveChannel<ContactServiceEvent>): List<ContactServiceEvent> {
    val adopted = mutableListOf<ContactServiceEvent>()
    while (true) {
        val event = events.tryReceive().getOrNull() ?: break
        if (event is ContactServiceEvent.OrphanDirectMessagesAdopted) adopted += event
    }
    return adopted
}

private class OrphanAdoptionFaultAdopter(private val fault: Throwable) : OrphanDmAdopter {
    var calls = 0

    override suspend fun adopt(radioID: Uuid, contacts: List<OrphanDmContact>): Set<Uuid> {
        calls += 1
        throw fault
    }
}

private class OrphanAdoptionFailure : Exception("disk")

private class OrphanAdoptionProbeError : Error("probe")
