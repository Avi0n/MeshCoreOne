package one.meshcore.shared.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal class ContactMaterializerLifetimeNativeFixture {
    val key = ByteArray(KEY_BYTES) { KEY_BYTE }
    val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val inner = InMemoryContactStore(newId = { CONTACT_ID })
    val requestedKeys = mutableListOf<ByteArray>()
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
    private val frame = MeshContact(
        id = "synthetic materializer", publicKey = key, typeRawValue = ContactType.CHAT.raw, flags = 0,
        outPathLength = 0, outPath = EMPTY, advertisedName = NAME, lastAdvertisement = 0,
        latitude = 0.0, longitude = 0.0, lastModified = 0,
    )
    private val session = object : ContactSession by NoOpContactSession() {
        override suspend fun getContact(publicKey: ByteArray): MeshContact? {
            requestedKeys += publicKey.copyOf()
            val result = if (publicKey.contentEquals(key)) {
                frame.copy(publicKey = frame.publicKey.copyOf(), outPath = frame.outPath.copyOf())
            } else {
                null
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
        now = { NOW },
        collaborators = ContactServiceCollaborators(
            syncCoordinator = CallbackContactSyncCoordinator(onContactsChanged = { refreshes++ }),
        ),
        edges = ContactServiceEdges(selfPublicKey = { ByteArray(KEY_BYTES) { SELF_BYTE } }),
    )

    suspend fun queue(): ReceiveChannel<ContactServiceEvent> {
        val events = service.events()
        service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
        assertFalse(service.handleAdvertisement(radio, key))
        assertTrue(service.hasPendingAdvertKey(key))
        assertTrue(store.fetchContacts(radio).isEmpty() && requestedKeys.isEmpty())
        return events
    }

    suspend fun invoke(): ContactDto? = service.materializeContactForPendingAdvert(key.copyOf(PREFIX_BYTES), radio)

    suspend fun assertEmpty() = assertTrue(store.fetchContacts(radio).isEmpty())

    suspend fun assertCommittedStamp() {
        val actual = requireNotNull(store.fetchContact(radio, key))
        val expected = ContactDto(
            id = CONTACT_ID, radioID = radio, publicKey = key, name = NAME, typeRawValue = ContactType.CHAT.raw,
            flags = 0, outPathLength = 0, outPath = EMPTY, lastAdvertTimestamp = 0,
            latitude = 0.0, longitude = 0.0, lastModified = 0, lastHeardTimestamp = NOW,
            isBlocked = false, isMuted = false, isFavorite = false, isPinned = false,
            unreadCount = 0, unreadMentionCount = 0,
        )
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
        const val KEY_BYTE: Byte = 0x6D
        const val SELF_BYTE: Byte = 0x11
        const val KEY_BYTES = 32
        const val PREFIX_BYTES = 6
        const val NAME = "Native pending lifetime peer"
        const val NOW = 1_700_000_000L
        val CONTACT_ID = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
        val EMPTY = ByteArray(0)
    }
}
