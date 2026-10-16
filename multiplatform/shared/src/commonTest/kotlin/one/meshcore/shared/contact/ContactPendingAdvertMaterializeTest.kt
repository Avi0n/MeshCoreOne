package one.meshcore.shared.contact

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A DM in the advert delta-sync debounce window needs a Contact row immediately so notify and the
 * Chats list can run. Materialize from a unique pending 0x80 key; leave the key queued. Mirrors iOS
 * `AdvertisementService.materializeContactForPendingAdvert` tests.
 */
class ContactPendingAdvertMaterializeTest {
    @Test
    fun uniquePendingKeyCreatesAContactAndStaysQueued() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x8D)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x8D, name = "DebounceNode")
        }
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertNull(store.fetchContact(radioID, key))
        assertTrue(!service.handleAdvertisement(radioID, key))

        val prefix = key.copyOf(PREFIX_LENGTH)
        val contact = service.materializeContactForPendingAdvert(matchingPrefix = prefix, radioID = radioID)
        assertNotNull(contact)
        assertTrue(contact.publicKey.contentEquals(key))
        assertEquals("DebounceNode", contact.name)
        assertEquals(CONTACT_TEST_NOW_SECONDS, contact.lastHeardTimestamp)
        assertEquals(listOf(key.toList()), session.getContactKeys.map { it.toList() })
        assertEquals(contact.id, store.fetchContact(radioID, key)?.id)

        // A second materialize still finds the queued key and does not drop it.
        val again = service.materializeContactForPendingAdvert(matchingPrefix = prefix, radioID = radioID)
        assertEquals(contact.id, again?.id)
        assertEquals(2, session.getContactKeys.size)
    }

    @Test
    fun missingPendingKeyDoesNotFetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val key = publicKey(0x8E)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x8E, name = "NoPending")
        }
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        val result = service.materializeContactForPendingAdvert(
            matchingPrefix = key.copyOf(PREFIX_LENGTH),
            radioID = radioID,
        )
        assertNull(result)
        assertTrue(session.getContactKeys.isEmpty())
        assertNull(store.fetchContact(radioID, key))
    }

    @Test
    fun ambiguousPrefixDoesNotFetch() = runTest {
        val radioID = ContactTestIds.RADIO
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        val sharedPrefix: Byte = 0xAB.toByte()
        val keyA = ByteArray(PUBLIC_KEY_SIZE) { 0x8F.toByte() }.also { it[0] = sharedPrefix }
        val keyB = ByteArray(PUBLIC_KEY_SIZE) { 0x90.toByte() }.also { it[0] = sharedPrefix }
        service.handleAdvertisement(radioID, keyA)
        service.handleAdvertisement(radioID, keyB)

        val result = service.materializeContactForPendingAdvert(
            matchingPrefix = byteArrayOf(sharedPrefix),
            radioID = radioID,
        )
        assertNull(result)
        assertTrue(session.getContactKeys.isEmpty())
    }

    @Test
    fun lastHeardStampFaultStillReturnsTheSavedContact() = runTest {
        val radioID = ContactTestIds.RADIO
        val inner = InMemoryContactStore()
        val key = publicKey(0x91)
        val store = FlakyTouchContactStore(inner, throwOnFirstTouches = 1)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = 0x91, name = "StampFault")
        }
        val service = testContactService(session = session, store = store)
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }

        assertTrue(!service.handleAdvertisement(radioID, key))
        val contact = service.materializeContactForPendingAdvert(
            matchingPrefix = key.copyOf(PREFIX_LENGTH),
            radioID = radioID,
        )
        assertNotNull(contact)
        assertEquals("StampFault", contact.name)
        assertNull(contact.lastHeardTimestamp)
        assertEquals(contact.id, inner.fetchContact(radioID, key)?.id)
        assertEquals(1, store.touchCalls)
    }

    private companion object {
        const val PREFIX_LENGTH = 6
        const val PUBLIC_KEY_SIZE = 32
    }
}
