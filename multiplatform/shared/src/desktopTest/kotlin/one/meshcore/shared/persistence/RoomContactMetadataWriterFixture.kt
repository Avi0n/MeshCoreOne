package one.meshcore.shared.persistence

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
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
import one.meshcore.shared.connection.QueuedAdvertHeardWriterDispatcher
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactMetadataWriterFixture(val kind: RoomContactMetadataWriterCase) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactMetadataWriterDispatcher(kind.frame)
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val json = Json { encodeDefaults = true }
    private val originEntered = CompletableDeferred<Job>()
    private var caller: Deferred<Unit>? = null
    private var original: Job? = null
    private var finiteWriter: Job? = null

    suspend fun seed() {
        rows.seed()
        val row = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            row.copy(
                unreadCount = OLD_UNREAD,
                unreadMentionCount = OLD_MENTIONS,
                lastHeardTimestamp = RoomContactMetadataWriterCase.NOW - OLD_HEARD_OFFSET,
            ),
        )
    }

    suspend fun start() {
        queries.arm()
        caller = scope.async(CoroutineName(QueuedContactMetadataWriterDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            invokeProduction(rows.firstContact, rows.firstKey, exists = true)
        }
        original = originEntered.await()
        assertTrue(original === caller)
        val work = queries.entered.await()
        finiteWriter = finiteRoomOperation(work, requireNotNull(original))
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(work.ran.get())
        assertTrue(work.frames.any { it.substringBefore('-') == kind.frame })
        assertTrue(work.frames.any(QueuedAdvertHeardWriterDispatcher::isOuterWriter))
        assertEquals(QueuedContactMetadataWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        val current = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertTrue(kind.expected(current) != current)
    }

    suspend fun editCurrent() {
        val current = requireNotNull(database.contactDao().getById(rows.firstContact))
        rows.store.saveContact(
            current.toDto().copy(
                nickname = "Fresh metadata nickname",
                avatarImageData = byteArrayOf(31, 32),
                unreadCount = FRESH_UNREAD,
                unreadMentionCount = FRESH_MENTIONS,
                lastHeardTimestamp = RoomContactMetadataWriterCase.NOW + FRESH_HEARD_OFFSET,
            ),
        )
        val committed = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertEquals("Fresh metadata nickname", committed.nickname)
        assertEquals(FRESH_UNREAD, committed.unreadCount)
        assertEquals(FRESH_MENTIONS, committed.unreadMentionCount)
        assertEquals(RoomContactMetadataWriterCase.NOW + FRESH_HEARD_OFFSET, committed.lastHeardTimestamp)
    }

    suspend fun expectedSnapshot(): Pair<JsonObject, List<PendingSendEntity>> {
        val before = rows.snapshot()
        val contacts = database.contactDao().listAll().sortedBy { it.id.value }
            .map { if (it.id == rows.firstContact) kind.expected(it).toDto() else it.toDto() }
        return JsonObject(before.first.toMutableMap().apply { put("contacts", json.encodeToJsonElement(contacts)) }) to
            before.second
    }

    fun cancelOrigin() {
        requireNotNull(caller).cancel()
        assertTrue(requireNotNull(original).isCancelled && requireNotNull(finiteWriter).isActive)
    }

    suspend fun releaseAndJoin(): Throwable? {
        queries.release()
        val cause = runCatching { requireNotNull(caller).await() }.exceptionOrNull()
        requireNotNull(finiteWriter).join()
        val work = queries.entered.await()
        work.returned.await()
        assertTrue(work.ran.get() && requireNotNull(finiteWriter).isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(cause == null || cause is CancellationException)
        assertTrue(scope.coroutineContext.job.isActive)
        return cause
    }

    suspend fun missingTarget() {
        val missing = Uuid.random()
        assertTrue(database.contactDao().getById(missing) == null)
        val missingKey = ByteArray(KEY_BYTES) { MISSING_KEY_BYTE }
        scope.async { invokeProduction(missing, missingKey, exists = false) }.await()
        assertFalse(queries.entered.isCompleted)
    }

    private suspend fun invokeProduction(id: Uuid, key: ByteArray, exists: Boolean) {
        when (kind) {
            RoomContactMetadataWriterCase.UNREAD -> rows.store.clearUnreadCount(id)
            RoomContactMetadataWriterCase.MENTIONS -> rows.store.clearUnreadMentionCount(id)
            RoomContactMetadataWriterCase.HEARD ->
                assertEquals(
                    exists,
                    rows.store.touchContactHeard(ChatFixtures.RADIO, key, RoomContactMetadataWriterCase.NOW),
                )
        }
    }

    private fun finiteRoomOperation(work: QueuedContactMetadataWriterDispatcher.Work, origin: Job): Job {
        val roomScope = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(roomScope.coroutineContext.job, work.context.job)
        assertNotSame(scope.coroutineContext.job, work.context.job)
        assertNotSame(origin, work.context.job)
        return work.context.job
    }

    fun close() {
        queries.release()
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    caller?.cancelAndJoin()
                    if (queries.entered.isCompleted) {
                        val work = queries.entered.await()
                        (finiteWriter ?: finiteRoomOperation(work, originEntered.await())).join()
                        work.returned.await()
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
        private const val OLD_UNREAD = 3
        private const val OLD_MENTIONS = 2
        private const val FRESH_UNREAD = 9
        private const val FRESH_MENTIONS = 5
        private const val OLD_HEARD_OFFSET = 60L
        private const val FRESH_HEARD_OFFSET = 30L
        private const val KEY_BYTES = 32
        private const val MISSING_KEY_BYTE: Byte = 0x07
    }
}
