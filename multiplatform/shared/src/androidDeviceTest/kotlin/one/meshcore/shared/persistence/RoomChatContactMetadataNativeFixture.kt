package one.meshcore.shared.persistence

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomChatContactMetadataNativeCase.HeardMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.io.File

internal class RoomChatContactMetadataNativeFixture(
    private val kind: RoomChatContactMetadataNativeCase,
    private val heardMode: HeardMode = HeardMode.NEW,
) {
    val dispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChatContactMetadataNativeWriterDispatcher()
    val rows = RoomContactDeleteAtomicityNativeFixture(queries)
    private val database get() = rows.database
    private val originalEntered = CompletableDeferred<Job>()
    private var publicJob: Job? = null
    private var originalJob: Job? = null
    private val discovered = RoomDiscoveredNodeStore(database.discoveredNodeDao())
    private var missing = false
    private var historyChanged = false
    private var expectedDiscover: List<one.meshcore.shared.contact.DiscoveredNodeRecord> = emptyList()
    private val chatStore = RoomChatStore(
        rows.radioID,
        database.chatDaos(),
        RxLogCorrelator(database.rxLogEntryDao(), nowEpochMs = { 0L }),
        discoveredNodes = discovered,
    )
    private fun capture(original: Job) {
        assertTrue(original === requireNotNull(publicJob))
        originalJob = original
        originalEntered.complete(original)
    }
    private val observed = CapturedChatContactMetadataNativeStores(chatStore, ::capture)
    private var caller: Deferred<Unit>? = null
    private var held: QueuedChatContactMetadataNativeWriterDispatcher.Work? = null

    suspend fun seed() {
        rows.seed()
        assertTrue(database.contactDao().getById(MISSING_ID) == null)
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(
                nickname = "Local nickname", avatarImageData = OLD_AVATAR.copyOf(), flags = SEED_FLAGS,
                isFavorite = false, isPinned = true, isMuted = true, unreadCount = 7, unreadMentionCount = 2,
                lastHeardTimestamp = heardMode.initial, lastMessageDate = RoomChatContactMetadataNativeCase.SEED_DATE,
                ocvPreset = "fixture curve", customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        discovered.upsert(
            kind.discovered(
                requireNotNull(database.contactDao().getById(rows.firstContact)),
                heardMode.discoverSeed,
            ),
        )
        assertTrue(rows.radioID != FOREIGN_RADIO)
        seedHistory()
    }

    private suspend fun seedHistory() {
        val messages = listOf(
            kind.message(
                LOCAL_HISTORY_ID,
                rows.radioID,
                rows.firstContact,
                RoomChatContactMetadataNativeCase.LOCAL_HISTORY_DATE,
            ),
            kind.message(
                OTHER_CONTACT_ID,
                rows.radioID,
                rows.secondContact,
                RoomChatContactMetadataNativeCase.OTHER_CONTACT_DATE,
            ),
            kind.message(
                CHANNEL_ID,
                rows.radioID,
                rows.firstContact,
                RoomChatContactMetadataNativeCase.CHANNEL_DATE,
                1,
            ),
            kind.message(
                OTHER_RADIO_ID,
                FOREIGN_RADIO,
                rows.firstContact,
                RoomChatContactMetadataNativeCase.OTHER_RADIO_DATE,
            ),
        )
        for (message in messages) {
            assertTrue(chatStore.fetchMessage(message.id) == null)
            chatStore.saveMessage(message)
            assertTrue(
                JSON.encodeToJsonElement(message) == JSON.encodeToJsonElement(chatStore.fetchMessage(message.id)),
            )
        }
    }

    suspend fun start(missingTarget: Boolean = false, gated: Boolean = true) {
        missing = missingTarget
        if (gated) queries.arm(FIRST_WRITER)
        caller = scope.async(CoroutineName(QueuedChatContactMetadataNativeWriterDispatcher.ORIGIN)) {
            publicJob = currentCoroutineContext().job
            kind.invoke(
                observed,
                rows.radioID,
                if (missing) MISSING_ID else rows.firstContact,
                heardMode.incoming,
            )
        }
        val original = originalEntered.await()
        assertTrue(publicJob === caller && original === caller)
        if (!gated) {
            requireNotNull(caller).await()
            assertTrue(original.isCompleted)
            return
        }
        held = queries.entered.await()
        val work = requireNotNull(held)
        assertTrue(publicJob === caller && original === caller)
        assertTrue(original.isActive && finiteOperation(work).isActive)
        assertFalse(work.ran.get())
        assertEquals(1, queries.works.size)
    }

    fun cancelCaller() {
        requireNotNull(caller).cancel()
        assertTrue(requireNotNull(originalJob).isCancelled)
        assertTrue(finiteOperation(requireNotNull(held)).isActive)
        assertFalse(requireNotNull(held).ran.get())
    }

    suspend fun editCurrent() {
        rows.store.updateContactFields(rows.firstContact) {
            it.copy(avatarImageData = FRESH_AVATAR.copyOf(), flags = FAVORITE_SEED_FLAGS, isFavorite = true)
        }
        val current = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertTrue(FRESH_AVATAR.contentEquals(current.avatarImageData))
        assertTrue(current.flags == FAVORITE_SEED_FLAGS && current.isFavorite)
        held?.let {
            assertTrue(originalEntered.await().isActive && finiteOperation(it).isActive)
            assertFalse(it.ran.get())
        }
    }

    suspend fun changeHistory() {
        assertTrue(kind == RoomChatContactMetadataNativeCase.RECOMPUTE)
        val message = kind.message(
            HISTORY_ID,
            rows.radioID,
            rows.firstContact,
            RoomChatContactMetadataNativeCase.HISTORY_DATE,
        )
        chatStore.saveMessage(message)
        assertTrue(JSON.encodeToJsonElement(message) == JSON.encodeToJsonElement(chatStore.fetchMessage(HISTORY_ID)))
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
            if (kind == RoomChatContactMetadataNativeCase.HEARD && !cancelled && !missingTarget) {
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
                    if (historyChanged) {
                        RoomChatContactMetadataNativeCase.HISTORY_DATE
                    } else {
                        RoomChatContactMetadataNativeCase.LOCAL_HISTORY_DATE
                    },
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

    suspend fun finish(cancelled: Boolean) {
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
        assertTrue(database.discoveredNodeDao().listAll().map { it.toRecord() } == discovered.snapshot.value)
    }

    private fun finiteOperation(work: QueuedChatContactMetadataNativeWriterDispatcher.Work): Job {
        val room = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(room.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(publicJob, work.context.job)
        assertNotSame(requireNotNull(originalJob), work.context.job)
        assertTrue(work.frames.any(QueuedChatContactMetadataNativeWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedChatContactMetadataNativeWriterDispatcher::isOuterWriter))
        return work.context.job
    }

    fun record(name: String, snapshotEqual: Boolean, survivorsEqual: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "chat-contact-metadata")
        check(directory.mkdirs() || directory.isDirectory)
        val workersCompleted = queries.works.all {
            it.ran.get() && it.context.job.isCompleted && it.returned.isCompleted && it.forwardCount.get() == 1
        }
        val originalIsCaller = originalJob === caller && publicJob === caller
        File(directory, "$name.json").writeText(
            JSONObject().put("expectedSnapshotEqual", snapshotEqual).put("survivorsEqual", survivorsEqual)
                .put("qualifiedFiniteWorkCount", queries.works.size).put("allWorkersCompleted", workersCompleted)
                .put("originCompleted", requireNotNull(originalJob).isCompleted)
                .put("originalIsActualCaller", originalIsCaller)
                .put("radioOrSessionConstructed", false).put("nativeRadioAckClaimed", false).toString(),
        )
    }

    fun close(name: String) {
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
                    rows.close(name)
                } finally {
                    check(!scope.coroutineContext.job.isActive)
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val FIRST_WRITER = 1
        private const val SEED_FLAGS = 0x10
        private const val FAVORITE_SEED_FLAGS = 0x11
        private val LOCAL_HISTORY_ID = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        private val OTHER_CONTACT_ID = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        private val CHANNEL_ID = Uuid.normalized("33333333-3333-3333-3333-333333333333")
        private val OTHER_RADIO_ID = Uuid.normalized("44444444-4444-4444-4444-444444444444")
        private val FOREIGN_RADIO = Uuid.normalized("DDDDDDDD-DDDD-DDDD-DDDD-DDDDDDDDDDDD")
        private val MISSING_ID = Uuid.normalized("77777777-7777-7777-7777-777777777777")
        private val HISTORY_ID = Uuid.normalized("66666666-6666-6666-6666-666666666666")
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
        private val JSON = Json { encodeDefaults = true }
    }
}
