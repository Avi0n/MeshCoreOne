package one.meshcore.shared.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ContactMaterializerLifetimeFixture {
    val key = publicKey(KEY_BYTE)
    val radio = ContactTestIds.RADIO
    private val inner = InMemoryContactStore(newId = { CONTACT_ID })
    val fake = FakeContactSession().apply {
        singleContactLookup = meshContact(keyByte = KEY_BYTE, name = NAME)
    }
    val completed = CompletableDeferred<Job>()
    val release = CompletableDeferred<Unit>()
    var holdReturn = false
    var onTouchCommitted: (() -> Unit)? = null
    var saves = 0
        private set
    var touches = 0
        private set
    var refreshes = 0
        private set
    private val session = object : ContactSession by fake {
        override suspend fun getContact(publicKey: ByteArray): MeshContact? {
            val result = fake.getContact(publicKey)?.let {
                it.copy(publicKey = it.publicKey.copyOf(), outPath = it.outPath.copyOf())
            }
            if (holdReturn) {
                assertNotNull(result)
                completed.complete(currentCoroutineContext().job)
                release.await()
            }
            return result
        }
    }
    val store = object : ContactStore by inner {
        override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
            saves++
            return inner.saveContact(radioID, frame)
        }

        override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
            touches++
            val wrote = inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
            assertTrue(wrote)
            onTouchCommitted?.invoke()
            return wrote
        }
    }
    val service = ContactService(
        session = session,
        store = store,
        now = { CONTACT_TEST_NOW_SECONDS },
        collaborators = ContactServiceCollaborators(
            syncCoordinator = CallbackContactSyncCoordinator(onContactsChanged = { refreshes++ }),
        ),
        edges = ContactServiceEdges(selfPublicKey = { ByteArray(KEY_BYTES) { SELF_BYTE } }),
    )

    suspend fun queue(): ReceiveChannel<ContactServiceEvent> {
        val events = service.events()
        queueSyncedAdvert(service, radio, key)
        assertTrue(service.hasPendingAdvertKey(key))
        assertTrue(store.fetchContacts(radio).isEmpty() && fake.getContactKeys.isEmpty())
        return events
    }

    suspend fun invoke(): ContactDto? = service.materializeContactForPendingAdvert(key.copyOf(PREFIX_BYTES), radio)

    suspend fun storedSnapshot(): List<List<Any?>> = store.fetchContacts(radio).map(::snapshot)

    suspend fun assertCommittedStamp() {
        val actual = requireNotNull(store.fetchContact(radio, key))
        val expected = contactDto(CONTACT_ID, radio, keyByte = KEY_BYTE, name = NAME)
            .copy(lastHeardTimestamp = CONTACT_TEST_NOW_SECONDS)
        assertEquals(snapshot(expected), snapshot(actual))
    }

    fun eventSnapshot(events: ReceiveChannel<ContactServiceEvent>): List<ContactServiceEvent> = buildList {
        while (true) add(events.tryReceive().getOrNull() ?: break)
    }

    private fun snapshot(row: ContactDto): List<Any?> = listOf(
        row.copy(publicKey = EMPTY, outPath = EMPTY, avatarImageData = null),
        row.publicKey.toList(),
        row.outPath.toList(),
        row.avatarImageData?.toList(),
    )

    private companion object {
        const val KEY_BYTE = 0x6D
        const val SELF_BYTE: Byte = 0x11
        const val KEY_BYTES = 32
        const val PREFIX_BYTES = 6
        const val NAME = "Pending lifetime peer"
        val CONTACT_ID = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
        val EMPTY = ByteArray(0)
    }
}
