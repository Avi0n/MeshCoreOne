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
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomChatContactMetadataFixture(
    private val kind: RoomChatContactMetadataCase,
    private val heardMode: RoomChatContactMetadataCase.HeardMode = RoomChatContactMetadataCase.HeardMode.NEW,
) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChatContactMetadataWriterDispatcher()
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
    private val discovered = RoomDiscoveredNodeStore(database.discoveredNodeDao())
    private var missing = false
    private var historyChanged = false
    private var expectedDiscover: List<one.meshcore.shared.contact.DiscoveredNodeRecord> = emptyList()
    private val chatStore = RoomChatStore(
        ChatFixtures.RADIO,
        database.chatDaos(),
        RxLogCorrelator(database.rxLogEntryDao(), nowEpochMs = { 0L }),
        discoveredNodes = discovered,
    )
    private fun capture(original: Job) {
        assertTrue(original === requireNotNull(publicJob))
        originalJob = original
        originalEntered.complete(original)
    }
    private val observed = CapturedChatContactMetadataStores(chatStore, ::capture)
    private val service = testContactService(
        session = session,
        store = rows.store,
        cleanup = cleanup,
        syncCoordinator = notifications,
    )
    private var caller: Deferred<Unit>? = null
    private var held: QueuedChatContactMetadataWriterDispatcher.Work? = null
    private var flagsContact: MeshContact? = null

    suspend fun seed() {
        rows.seed()
        assertEquals(null, database.contactDao().getById(MISSING_ID))
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(
                nickname = "Local nickname", avatarImageData = OLD_AVATAR.copyOf(), flags = SEED_FLAGS,
                isFavorite = false, isPinned = true, isMuted = true, unreadCount = 7, unreadMentionCount = 2,
                lastHeardTimestamp = heardMode.initial, lastMessageDate = RoomChatContactMetadataCase.SEED_DATE,
                ocvPreset = "fixture curve", customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        discovered.upsert(
            ContactMetadataExpected.discovered(
                requireNotNull(database.contactDao().getById(rows.firstContact)),
                heardMode.discoverSeed,
            ),
        )
        assertEffects(edited = false)
    }

    suspend fun start(missingTarget: Boolean = false, gated: Boolean = true) {
        missing = missingTarget
        if (gated) queries.arm(FIRST_WRITER)
        caller = scope.async(CoroutineName(QueuedChatContactMetadataWriterDispatcher.ORIGIN)) {
            publicJob = currentCoroutineContext().job
            kind.invoke(observed, if (missing) MISSING_ID else rows.firstContact, heardMode.incoming)
        }
        val original = originalEntered.await()
        assertTrue(publicJob === caller && original === caller)
        if (!gated) {
            requireNotNull(caller).await()
            assertTrue(original.isCompleted)
            assertEffects(edited = false)
            return
        }
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

    suspend fun changeHistory() {
        assertTrue(kind == RoomChatContactMetadataCase.RECOMPUTE)
        val message = ChatFixtures.message(
            id = HISTORY_ID,
            contactID = rows.firstContact,
            text = "Synthetic newer direct history",
            sortDate = RoomChatContactMetadataCase.HISTORY_DATE,
        )
        chatStore.saveMessage(message)
        assertEquals(JSON.encodeToJsonElement(message), JSON.encodeToJsonElement(chatStore.fetchMessage(HISTORY_ID)))
        historyChanged = true
        assertTrue(originalEntered.await().isActive && finiteOperation(requireNotNull(held)).isActive)
        assertFalse(requireNotNull(held).ran.get())
    }

    suspend fun expectedSnapshot(
        cancelled: Boolean,
        missingTarget: Boolean,
    ): Pair<Pair<JsonObject, List<PendingSendEntity>>, List<one.meshcore.shared.contact.DiscoveredNodeRecord>> {
        val before = rows.snapshot()
        expectedDiscover = discovered.snapshot.value.map { row ->
            if (kind == RoomChatContactMetadataCase.HEARD && !cancelled && !missingTarget) {
                row.copy(lastHeardEpochSeconds = heardMode.expected)
            } else {
                row
            }
        }
        if (cancelled || missingTarget) return before to expectedDiscover
        val contacts = database.contactDao().listAll().sortedBy { it.id.value }.map { row ->
            if (row.id == rows.firstContact) {
                kind.expected(
                    row,
                    if (historyChanged) RoomChatContactMetadataCase.HISTORY_DATE else ChatFixtures.BASE_EPOCH_SECONDS,
                    heardMode.expected,
                ).toDto()
            } else {
                row.toDto()
            }
        }
        return (
            JsonObject(before.first.toMutableMap().apply { put("contacts", JSON.encodeToJsonElement(contacts)) }) to
                before.second
            ) to expectedDiscover
    }

    suspend fun snapshot() = rows.snapshot() to database.discoveredNodeDao().listAll().map { it.toRecord() }

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
        assertEquals(database.discoveredNodeDao().listAll().map { it.toRecord() }, discovered.snapshot.value)
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

    private fun finiteOperation(work: QueuedChatContactMetadataWriterDispatcher.Work): Job {
        val room = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(room.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(publicJob, work.context.job)
        assertNotSame(requireNotNull(originalJob), work.context.job)
        assertTrue(work.frames.any(QueuedChatContactMetadataWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedChatContactMetadataWriterDispatcher::isOuterWriter))
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
        private val MISSING_ID = Uuid.normalized("77777777-7777-7777-7777-777777777777")
        private val HISTORY_ID = Uuid.normalized("66666666-6666-6666-6666-666666666666")
        private val EMPTY = ByteArray(0)
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
        private val JSON = Json { encodeDefaults = true }
    }
}
