package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ZephCore V-contact is protected from MC1's own cleanup: it is not pruned on a full sync (it is absent
 * from GET_CONTACTS while clock-deferred or disabled, not a real orphan), and remove paths are disabled so
 * no `CMD_REMOVE` turns the firmware feature off. Mirrors the iOS `ContactService` V-contact hooks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactServiceVContactTest {
    private val radioID = ContactTestIds.RADIO
    private val selfKey = ByteArray(32) { 0x11 }
    private val vKey = VContactIdentity.publicKey(selfKey)!!

    private fun vContactFrame() = ContactFrame(
        publicKey = vKey,
        type = ContactType.CHAT,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        name = "V-Contact",
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
    )

    @Test
    fun fullSyncDoesNotPruneTheVContact() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(radioID, vContactFrame())
        store.saveContact(radioID, contactFrame(0xDD, name = "Stale"))

        val session = FakeContactSession()
        // The device reports only Alice; neither the V-contact nor the stale row is present.
        session.stubbedContacts = listOf(meshContact(0xAA, name = "Alice"))

        val service = testContactService(session = session, store = store, selfPublicKey = { selfKey })
        service.syncContacts(radioID, since = null)

        assertNotNull(store.fetchContact(radioID, vKey), "V-contact survives the full-sync prune")
        assertNull(store.fetchContact(radioID, publicKey(0xDD)), "an ordinary orphan is still pruned")
    }

    @Test
    fun removeDumpsMatchIos() {
        assertEquals(
            "removeContact ignored for ZephCore V-contact (remove disabled)",
            REMOVE_CONTACT_IGNORED_V_CONTACT,
        )
        assertEquals(
            "removeLocalContact ignored for ZephCore V-contact (remove disabled)",
            REMOVE_LOCAL_CONTACT_IGNORED_V_CONTACT,
        )
    }

    @Test
    fun removeContactIsBlockedForTheVContact() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryContactStore()
        store.saveContact(radioID, vContactFrame())
        val session = FakeContactSession()

        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = testContactService(
            session = session,
            store = store,
            selfPublicKey = { selfKey },
            logger = PersistentLogger(
                scope = this,
                category = "ContactService",
                record = { recorded += it },
            ),
        )
        service.removeContact(radioID, vKey)

        assertTrue(session.removedPublicKeys.isEmpty(), "no CMD_REMOVE is sent for the V-contact")
        assertNotNull(store.fetchContact(radioID, vKey), "the V-contact local row is preserved")
        val line = recorded.single { it.message == REMOVE_CONTACT_IGNORED_V_CONTACT }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("ContactService", line.category)
    }

    @Test
    fun removeLocalContactIsBlockedForTheVContact() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryContactStore()
        store.saveContact(radioID, vContactFrame())
        val cleanup = RecordingCleanupHandler()
        val vContactId = store.fetchContact(radioID, vKey)!!.id

        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = testContactService(
            store = store,
            cleanup = cleanup,
            selfPublicKey = { selfKey },
            logger = PersistentLogger(
                scope = this,
                category = "ContactService",
                record = { recorded += it },
            ),
        )
        service.removeLocalContact(vContactId, vKey)

        assertNotNull(store.fetchContact(radioID, vKey), "the V-contact local row is preserved")
        assertTrue(cleanup.invocations.isEmpty(), "no cleanup runs for the V-contact")
        val line = recorded.single { it.message == REMOVE_LOCAL_CONTACT_IGNORED_V_CONTACT }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("ContactService", line.category)
    }

    @Test
    fun handleDeviceContactDeletedPreservesTheVContact() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(radioID, vContactFrame())
        val cleanup = RecordingCleanupHandler()
        val service = testContactService(store = store, cleanup = cleanup, selfPublicKey = { selfKey })
        val events = service.events()

        assertFalse(service.handleDeviceContactDeleted(radioID, vKey))
        assertNotNull(store.fetchContact(radioID, vKey), "0x8F for the V-key is not overwrite-oldest")
        assertTrue(cleanup.invocations.isEmpty(), "no cleanup runs for the V-contact")
        assertTrue(events.tryReceive().isFailure, "NodeDeleted must not fire for the V-contact")
    }

    @Test
    fun cancelledSelfKeyReadDoesNotDeleteAnOrdinaryContact() = runTest {
        val store = InMemoryContactStore()
        val key = publicKey(0xCC)
        store.saveContact(radioID, contactFrame(0xCC, name = "Carol"))
        val stopped = CancellationException("superseded")
        val service = testContactService(store = store, selfPublicKey = { throw stopped })

        val thrown = assertFailsWith<CancellationException> { service.handleDeviceContactDeleted(radioID, key) }

        assertSame(stopped, thrown)
        assertNotNull(store.fetchContact(radioID, key))
    }

    @Test
    fun removeContactStillWorksForAnOrdinaryContact() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(radioID, contactFrame(0xCC, name = "Carol"))
        val session = FakeContactSession()

        val service = testContactService(session = session, store = store, selfPublicKey = { selfKey })
        service.removeContact(radioID, publicKey(0xCC))

        assertTrue(
            session.removedPublicKeys.any { it.contentEquals(publicKey(0xCC)) },
            "ordinary remove sends CMD_REMOVE",
        )
        assertNull(store.fetchContact(radioID, publicKey(0xCC)), "ordinary contact row is deleted")
    }
}
