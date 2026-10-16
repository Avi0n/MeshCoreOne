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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.MessageStore
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeContactStore
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactPeerFrameFixture(private val kind: RoomContactPeerFrameCase) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactPeerWriterDispatcher()
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
    private val nodeStore = RoomNodeStore(
        radioId = ChatFixtures.RADIO,
        sessionDao = database.remoteNodeSessionDao(),
        roomMessageDao = database.roomMessageDao(),
        contactDao = database.contactDao(),
        now = { 0.0 },
    )
    private val chatStore = RoomChatStore(
        ChatFixtures.RADIO,
        database.chatDaos(),
        RxLogCorrelator(database.rxLogEntryDao(), nowEpochMs = { 0L }),
    )
    private fun capture(original: Job) {
        assertTrue(original === requireNotNull(publicJob))
        originalJob = original
        originalEntered.complete(original)
    }
    private val observedNode = object : RemoteNodeContactStore by nodeStore {
        override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {
            capture(currentCoroutineContext().job)
            nodeStore.saveContact(radioID, frame)
        }
    }
    private val observedChat = object : MessageStore by chatStore {
        override suspend fun saveContact(radioID: Uuid, contact: MeshContact) {
            capture(currentCoroutineContext().job)
            chatStore.saveContact(radioID, contact)
        }
    }
    private val service = testContactService(
        session = session,
        store = rows.store,
        cleanup = cleanup,
        syncCoordinator = notifications,
    )
    private var caller: Deferred<Unit>? = null
    private var held: QueuedContactPeerWriterDispatcher.Work? = null
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
        assertEffects(edited = false)
    }

    suspend fun start() {
        queries.arm(FIRST_WRITER)
        caller = scope.async(CoroutineName(QueuedContactPeerWriterDispatcher.ORIGIN)) {
            publicJob = currentCoroutineContext().job
            kind.invoke(observedNode, observedChat, ChatFixtures.RADIO, frame)
        }
        val original = originalEntered.await()
        held = queries.entered.await()
        val work = requireNotNull(held)
        assertTrue(publicJob === caller && original === caller)
        assertTrue(original.isActive && finiteOperation(work).isActive)
        assertFalse(work.ran.get())
        assertEquals(1, queries.works.size)
        assertEffects(edited = false)
    }

    fun cancelCaller() {
        requireNotNull(caller).cancel()
        assertTrue(requireNotNull(originalJob).isCancelled)
        assertTrue(finiteOperation(requireNotNull(held)).isActive)
        assertFalse(requireNotNull(held).ran.get())
    }

    suspend fun editCurrent() {
        flagsContact = requireNotNull(database.contactDao().getById(rows.firstContact)).toDto().toMeshContact()
        service.updateContactAvatar(rows.firstContact, FRESH_AVATAR.copyOf())
        service.setContactFavorite(rows.firstContact, true)
        val current = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertContentEquals(FRESH_AVATAR, current.avatarImageData)
        assertEquals(FAVORITE_SEED_FLAGS, current.flags)
        assertTrue(current.isFavorite)
        held?.let {
            assertTrue(originalEntered.await().isActive && finiteOperation(it).isActive)
            assertFalse(it.ran.get())
        }
        assertEffects(edited = true)
    }

    suspend fun expectedSnapshot(edited: Boolean, cancelled: Boolean): Pair<JsonObject, List<PendingSendEntity>> {
        val before = rows.snapshot()
        if (cancelled) return before
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

    suspend fun finish(edited: Boolean, cancelled: Boolean) {
        queries.release()
        val result = runCatching { requireNotNull(caller).await() }
        queries.works.forEach { work ->
            finiteOperation(work).join()
            work.returned.await()
            assertTrue(work.ran.get() && finiteOperation(work).isCompleted)
            assertEquals(1, work.forwardCount.get())
        }
        assertTrue(originalEntered.await().isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        if (cancelled) {
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        } else {
            assertTrue(result.isSuccess)
            assertEquals(Unit, result.getOrNull())
        }
        assertEffects(edited)
    }

    private fun assertEffects(edited: Boolean) {
        assertEquals(if (edited) 1 else 0, notifications.contactsChanged)
        assertEquals(0, notifications.conversationsChanged)
        assertTrue(cleanup.invocations.isEmpty())
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(if (edited) 1 else 0, session.changeFlagsCalls.size)
        if (edited) {
            val command = session.changeFlagsCalls.single()
            assertEquals(normalized(requireNotNull(flagsContact)), normalized(command.first))
            assertEquals(FAVORITE_SEED_FLAGS, command.second)
        }
        assertTrue(session.removedPublicKeys.isEmpty() && session.resetPathPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty() && session.pathDiscoveryDestinations.isEmpty())
        assertTrue(session.getContactKeys.isEmpty())
    }

    private fun finiteOperation(work: QueuedContactPeerWriterDispatcher.Work): Job {
        val room = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(room.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(publicJob, work.context.job)
        assertNotSame(requireNotNull(originalJob), work.context.job)
        assertTrue(work.frames.any(QueuedContactPeerWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedContactPeerWriterDispatcher::isOuterWriter))
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
                try {
                    rows.close()
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val FIRST_WRITER = 1
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
