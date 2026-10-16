package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on Danger Zone unfavorited count/remove rethrows so the screen can show mapped
 * copy (iOS `unfavoritedNodeCount` / `removeContacts` throw; `DangerZoneViewModel` catches).
 *
 * Bulk remove also skips the ZephCore V-contact the way iOS `removeContacts` does: `CMD_REMOVE`
 * would turn firmware `v.contact` off. The count still includes it, matching `unfavoritedNodeCount`.
 */
class UnfavoritedNodeRemovalTest {
    private val selfKey = ByteArray(32) { 0x11 }

    @Test
    fun countPersistFaultRethrows() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        assertFailsWith<IllegalStateException> {
            unfavoritedNodeCount(ContactTestIds.RADIO, testContactService(store = store))
        }
    }

    @Test
    fun removePersistFaultRethrows() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContacts(radioID: Uuid) = error("db locked")
        }
        assertFailsWith<IllegalStateException> {
            removeUnfavoritedNodes(ContactTestIds.RADIO, testContactService(store = store))
        }
    }

    @Test
    fun countIncludesTheUnfavoritedVContact() = runTest {
        val store = InMemoryContactStore()
        val vKey = requireNotNull(VContactIdentity.publicKey(selfKey))
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "V")
                .copy(publicKey = vKey),
        )
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, keyByte = 0x22, name = "Alice"),
        )
        val service = testContactService(store = store, selfPublicKey = { selfKey })

        assertEquals(2, unfavoritedNodeCount(ContactTestIds.RADIO, service))
    }

    @Test
    fun removeSkipsTheVContactAndRemovesOrdinaryUnfavorited() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val vKey = requireNotNull(VContactIdentity.publicKey(selfKey))
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "V")
                .copy(publicKey = vKey),
        )
        val alice = contactDto(
            id = ContactTestIds.fresh(),
            radioID = ContactTestIds.RADIO,
            keyByte = 0x22,
            name = "Alice",
        )
        store.saveContact(alice)
        val service = testContactService(session = session, store = store, selfPublicKey = { selfKey })

        val result = removeUnfavoritedNodes(ContactTestIds.RADIO, service)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertEquals(1, session.removedPublicKeys.size)
        assertTrue(session.removedPublicKeys.single().contentEquals(alice.publicKey))
        assertNotNull(store.fetchContact(ContactTestIds.RADIO, vKey))
        assertEquals("V", store.fetchContacts(ContactTestIds.RADIO).single().name)
    }

    @Test
    fun aForgottenRadioContactIsRemovedLocally() = runTest {
        val store = InMemoryContactStore()
        val alice = contactDto(
            id = ContactTestIds.fresh(),
            radioID = ContactTestIds.RADIO,
            keyByte = 0x22,
            name = "Alice",
        )
        store.saveContact(alice)
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session, store = store)

        val result = removeUnfavoritedNodes(ContactTestIds.RADIO, service)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(store.fetchContacts(ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun aRadioFailureStopsThePass() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "Alice"))
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, keyByte = 0x22, name = "Bob"),
        )
        var attempts = 0
        val session = FakeContactSession().apply {
            onRemoveContact = {
                attempts += 1
                error("radio down")
            }
        }
        val service = testContactService(session = session, store = store)

        val result = removeUnfavoritedNodes(ContactTestIds.RADIO, service)

        assertEquals(StaleNodeRemovalResult(removed = 0, total = 2), result)
        assertEquals(1, attempts)
        assertEquals(2, store.fetchContacts(ContactTestIds.RADIO).size)
    }

    @Test
    fun aStoreErrorDuringRemoveStopsThePass() = runTest {
        val backing = InMemoryContactStore()
        backing.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "Alice"))
        backing.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, keyByte = 0x22, name = "Bob"),
        )
        var deletes = 0
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) {
                deletes += 1
                throw UnfavoritedRemovalProbeError()
            }
        }
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)

        val result = removeUnfavoritedNodes(ContactTestIds.RADIO, service)

        assertEquals(StaleNodeRemovalResult(removed = 0, total = 2), result)
        assertEquals(1, deletes)
        assertEquals(1, session.removedPublicKeys.size)
    }

    @Test
    fun aCancelledRadioRemovePropagates() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "Alice"))
        val cancelled = CancellationException("stopped")
        val session = FakeContactSession().apply { removeContactThrowable = cancelled }
        val service = testContactService(session = session, store = store)

        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                removeUnfavoritedNodes(ContactTestIds.RADIO, service)
            },
        )
    }

    @Test
    fun aLocalCleanupFailureSkipsAndContinues() = runTest {
        val backing = InMemoryContactStore()
        val alice = contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "Alice")
        val bob = contactDto(
            id = ContactTestIds.fresh(),
            radioID = ContactTestIds.RADIO,
            keyByte = 0x22,
            name = "Bob",
        )
        backing.saveContact(alice)
        backing.saveContact(bob)
        var deletes = 0
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) {
                deletes += 1
                if (deletes == 1) error("disk full")
                backing.deleteContact(id)
            }
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session, store = store)

        val result = removeUnfavoritedNodes(ContactTestIds.RADIO, service)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 2), result)
        assertEquals(2, deletes)
        assertEquals(1, store.fetchContacts(ContactTestIds.RADIO).size)
    }

    @Test
    fun aCancelledLocalCleanupPropagates() = runTest {
        val backing = InMemoryContactStore()
        backing.saveContact(contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "Alice"))
        val cancelled = CancellationException("stopped")
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid): Unit = throw cancelled
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session, store = store)

        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                removeUnfavoritedNodes(ContactTestIds.RADIO, service)
            },
        )
    }
}

private class UnfavoritedRemovalProbeError : Error("probe")
