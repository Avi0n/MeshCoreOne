package one.meshcore.shared.persistence

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactSyncResult
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactFrameMergeFixture(val kind: RoomContactFrameMergeCase, private val ordinal: Int) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactFrameWriterDispatcher()
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val session = FakeContactSession()
    private val cleanup = RecordingCleanupHandler()
    private val notifications = RecordingContactSyncCoordinator()
    private val originalEntered = CompletableDeferred<Job>()
    private var publicJob: Job? = null
    private var originalJob: Job? = null
    private val observedStore = CapturedFrameStore(rows.store) { original ->
        val external = requireNotNull(publicJob)
        if (kind == RoomContactFrameMergeCase.SINGLE) {
            assertTrue(original === external)
        } else {
            assertNotSame(external, original)
            assertTrue(external.children.any { it === original })
        }
        originalJob = original
        originalEntered.complete(original)
    }
    private val service = testContactService(
        session = session,
        store = observedStore,
        cleanup = cleanup,
        syncCoordinator = notifications,
    )
    private var caller: Deferred<ContactSyncResult?>? = null
    private var events: ReceiveChannel<ContactServiceEvent>? = null
    private var held: QueuedContactFrameWriterDispatcher.Work? = null
    private var flagsContact: MeshContact? = null
    private val frame = ContactFrame(
        publicKey = rows.firstKey.copyOf(), type = ContactType.CHAT, flags = FRAME_FLAGS,
        outPathLength = FRAME_PATH_LENGTH, outPath = byteArrayOf(11, 12), name = FRAME_NAME,
        lastAdvertTimestamp = FRAME_ADVERT, latitude = FRAME_LATITUDE, longitude = FRAME_LONGITUDE,
        lastModified = FRAME_MODIFIED,
    )

    suspend fun seed() {
        rows.seed()
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(
                nickname = "Local nickname", avatarImageData = OLD_AVATAR.copyOf(), flags = SEED_FLAGS,
                isFavorite = false, isPinned = true, isMuted = true, unreadCount = 7, unreadMentionCount = 2,
                lastHeardTimestamp = OLD_HEARD, ocvPreset = "fixture curve", customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        session.stubbedContacts = listOf(frame.toMeshContact())
        events = service.events()
        assertEffects(edited = false, completed = false)
    }

    suspend fun start(): Boolean {
        queries.arm(ordinal)
        caller = scope.async(CoroutineName(QueuedContactFrameWriterDispatcher.ORIGIN)) {
            publicJob = currentCoroutineContext().job
            kind.invoke(service, requireNotNull(rows.store.fetchContact(rows.firstContact)).radioID, frame)
        }
        val original = originalEntered.await()
        val gated = select {
            queries.entered.onAwait { work ->
                held = work
                true
            }
            requireNotNull(caller).onAwait { false }
        }
        assertTrue(publicJob === caller)
        if (gated) {
            val work = requireNotNull(held)
            assertTrue(original.isActive && requireNotNull(caller).isActive && finiteOperation(work).isActive)
            assertFalse(work.ran.get())
            assertEquals(ordinal, queries.works.size)
        } else {
            assertEquals(SECOND_WRITER, ordinal)
            assertEquals(1, queries.works.size)
            assertTrue(requireNotNull(caller).isCompleted && original.isCompleted)
        }
        if (ordinal == SECOND_WRITER) {
            val first = queries.works.first()
            finiteOperation(first).join()
            first.returned.await()
            val persisted = requireNotNull(database.contactDao().getById(rows.firstContact))
            assertEquals(FRAME_NAME, persisted.name)
            assertEquals(FRAME_FLAGS, persisted.flags)
            assertContentEquals(frame.outPath, persisted.outPath)
        }
        assertEquals(kind == RoomContactFrameMergeCase.INCREMENTAL && gated, service.isSyncingContacts)
        assertEffects(edited = false, completed = !gated)
        return gated
    }

    suspend fun editCurrent() {
        flagsContact = requireNotNull(database.contactDao().getById(rows.firstContact)).toDto().toMeshContact()
        service.updateContactAvatar(rows.firstContact, FRESH_AVATAR.copyOf())
        service.setContactFavorite(rows.firstContact, true)
        val current = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertContentEquals(FRESH_AVATAR, current.avatarImageData)
        assertEquals(if (ordinal == SECOND_WRITER) FAVORITE_FRAME_FLAGS else FAVORITE_SEED_FLAGS, current.flags)
        assertTrue(current.isFavorite)
        held?.let {
            assertTrue(originalEntered.await().isActive && finiteOperation(it).isActive)
            assertFalse(it.ran.get())
        }
        assertEffects(edited = true, completed = held == null)
    }

    suspend fun expectedSnapshot(edited: Boolean): Pair<JsonObject, List<PendingSendEntity>> {
        val before = rows.snapshot()
        val contacts = database.contactDao().listAll().sortedBy { it.id.value }.map { row ->
            if (row.id == rows.firstContact) {
                row.copy(
                    name = FRAME_NAME, typeRawValue = ContactType.CHAT.raw,
                    flags = if (edited) FAVORITE_FRAME_FLAGS else FRAME_FLAGS,
                    outPathLength = FRAME_PATH_LENGTH, outPath = frame.outPath.copyOf(),
                    lastAdvertTimestamp = FRAME_ADVERT, latitude = FRAME_LATITUDE, longitude = FRAME_LONGITUDE,
                    lastModified = FRAME_MODIFIED,
                ).toDto()
            } else {
                row.toDto()
            }
        }
        return JsonObject(before.first.toMutableMap().apply { put("contacts", JSON.encodeToJsonElement(contacts)) }) to
            before.second
    }

    suspend fun finish(edited: Boolean) {
        queries.release()
        val result = requireNotNull(caller).await()
        queries.works.forEach { work ->
            finiteOperation(work).join()
            work.returned.await()
            assertTrue(work.ran.get() && finiteOperation(work).isCompleted)
            assertEquals(1, work.forwardCount.get())
        }
        assertTrue(originalEntered.await().isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(
            if (kind == RoomContactFrameMergeCase.INCREMENTAL) ContactSyncResult(1, FRAME_MODIFIED, true) else null,
            result,
        )
        assertFalse(service.isSyncingContacts)
        val received = buildList { while (true) add(requireNotNull(events).tryReceive().getOrNull() ?: break) }
        val expected = if (kind == RoomContactFrameMergeCase.INCREMENTAL) {
            listOf(ContactServiceEvent.SyncProgress(0, 1), ContactServiceEvent.SyncProgress(1, 1))
        } else {
            emptyList()
        }
        assertEquals(expected, received)
        assertEffects(edited, completed = true)
    }

    private fun assertEffects(edited: Boolean, completed: Boolean) {
        assertEquals(
            (if (kind == RoomContactFrameMergeCase.SINGLE && completed) 1 else 0) + if (edited) 1 else 0,
            notifications.contactsChanged,
        )
        assertEquals(0, notifications.conversationsChanged)
        assertTrue(cleanup.invocations.isEmpty())
        val expectedAdds = if (publicJob != null && kind == RoomContactFrameMergeCase.SINGLE) 1 else 0
        assertEquals(expectedAdds, session.addedContacts.size)
        if (expectedAdds !=
            0
        ) {
            assertEquals(normalized(frame.toMeshContact()), normalized(session.addedContacts.single()))
        }
        assertEquals(if (edited) 1 else 0, session.changeFlagsCalls.size)
        if (edited) {
            val command = session.changeFlagsCalls.single()
            assertEquals(normalized(requireNotNull(flagsContact)), normalized(command.first))
            assertEquals(if (ordinal == SECOND_WRITER) FAVORITE_FRAME_FLAGS else FAVORITE_SEED_FLAGS, command.second)
        }
        assertTrue(session.removedPublicKeys.isEmpty() && session.resetPathPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty() && session.pathDiscoveryDestinations.isEmpty())
        assertTrue(session.getContactKeys.isEmpty())
    }

    private fun finiteOperation(work: QueuedContactFrameWriterDispatcher.Work): Job {
        val room = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(room.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(publicJob, work.context.job)
        assertNotSame(requireNotNull(originalJob), work.context.job)
        assertTrue(work.frames.any(QueuedContactFrameWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedContactFrameWriterDispatcher::isOuterWriter))
        return work.context.job
    }

    private fun normalized(contact: MeshContact): List<Any> = listOf(
        contact.copy(publicKey = EMPTY, outPath = EMPTY),
        contact.publicKey.toList(),
        contact.outPath.toList(),
    )

    fun close() {
        queries.release()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    caller?.cancelAndJoin()
                    queries.works.forEach {
                        finiteOperation(it).join()
                        it.returned.await()
                    }
                }
            }
        } finally {
            try {
                runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() } }
            } finally {
                events?.cancel()
                try {
                    rows.close()
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    private class CapturedFrameStore(private val delegate: ContactStore, private val capture: (Job) -> Unit) :
        ContactStore by delegate {
        override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
            capture(currentCoroutineContext().job)
            return delegate.saveContact(radioID, frame)
        }
        override suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int {
            capture(currentCoroutineContext().job)
            return delegate.batchSaveContacts(radioID, frames)
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val FIRST_WRITER = 1
        const val SECOND_WRITER = 2
        private const val SEED_FLAGS = 0x10
        private const val FAVORITE_SEED_FLAGS = 0x11
        private const val FRAME_FLAGS = 0x0E
        private const val FAVORITE_FRAME_FLAGS = 0x0F
        private const val FRAME_PATH_LENGTH = 2
        private const val FRAME_NAME = "Updated radio name"
        private const val FRAME_ADVERT = 1_700_000_300L
        private const val FRAME_MODIFIED = 1_700_000_200L
        private const val FRAME_LATITUDE = 45.2
        private const val FRAME_LONGITUDE = -122.3
        private const val OLD_HEARD = 1_700_000_000L
        private val EMPTY = ByteArray(0)
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
        private val JSON = Json { encodeDefaults = true }
    }
}
