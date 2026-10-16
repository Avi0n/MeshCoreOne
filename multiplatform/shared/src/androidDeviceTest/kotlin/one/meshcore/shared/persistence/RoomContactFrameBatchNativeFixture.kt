package one.meshcore.shared.persistence

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

internal class RoomContactFrameBatchNativeFixture(private val fault: Throwable?) {
    private val dispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = RecordingDispatcher()
    val rows = RoomContactDeleteAtomicityNativeFixture(queries)
    private val database get() = rows.database
    private val minted = CopyOnWriteArrayList<Uuid>()
    private val secondWritten = CompletableDeferred<Job>()
    private val releaseSecond = CompletableDeferred<Unit>()
    private var writes = 0
    private var callbackJob: Job? = null
    private var external: Job? = null
    private var original: Job? = null
    private var caller: Deferred<Int>? = null
    private lateinit var initialContacts: List<ContactDto>
    private lateinit var target: ContactDto
    private val actualStore = RoomContactStore(
        RoomContactParts(
            database,
            newId = { Uuid.random().also { minted.add(it) } },
            contactDao = object : ContactDao by database.contactDao() {
                override suspend fun upsertPreservingPin(entity: ContactEntity) {
                    database.contactDao().upsertPreservingPin(entity)
                    writes++
                    if (writes == SECOND_WRITE) observeSecondWrite()
                }
            },
        ),
    )
    private val observedStore = object : ContactStore by actualStore {
        override suspend fun batchSaveContacts(radioID: Uuid, frames: List<ContactFrame>): Int {
            original = currentCoroutineContext().job
            val public = requireNotNull(external)
            assertTrue(public === original)
            return actualStore.batchSaveContacts(radioID, frames)
        }
    }
    private lateinit var frames: List<ContactFrame>
    private val insertedFrame = ContactFrame(
        publicKey = ByteArray(KEY_BYTES) { NEW_KEY_BYTE.toByte() }, type = ContactType.CHAT,
        flags = FAVORITE_FLAG, outPathLength = 0, outPath = ByteArray(0), name = FIRST_NAME,
        lastAdvertTimestamp = 0, latitude = 0.0, longitude = 0.0, lastModified = FIRST_TIME,
    )

    suspend fun seed() {
        rows.seed()
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(flags = FAVORITE_FLAG, isFavorite = true, isPinned = true, nickname = "Local"),
        )
        initialContacts = database.contactDao().listAll().map { it.toDto() }
        target = initialContacts.single { it.id == rows.firstContact }
        val update = insertedFrame.copy(
            name = SECOND_NAME,
            publicKey = rows.firstKey.copyOf(),
            flags = NON_FAVORITE_FLAG,
            lastModified = SECOND_TIME,
        )
        val repeated = insertedFrame.copy(name = REPEATED_NAME, flags = NON_FAVORITE_FLAG, lastModified = REPEAT_TIME)
        frames = listOf(insertedFrame, update, repeated)
        assertTrue(database.contactDao().listAll().none { it.publicKey.contentEquals(insertedFrame.publicKey) })
    }

    suspend fun start() {
        caller = scope.async(CoroutineName(ORIGIN)) {
            external = currentCoroutineContext().job
            observedStore.batchSaveContacts(target.radioID, frames)
        }
        val local = secondWritten.await()
        assertFinite(local)
        assertTrue(external === caller)
        if (fault == null) {
            assertTrue(local.isActive && requireNotNull(original).isActive && requireNotNull(caller).isActive)
        }
        assertEquals(1, minted.size)
    }

    fun cancelCaller() {
        requireNotNull(caller).cancel()
        assertTrue(requireNotNull(original).isCancelled && requireNotNull(caller).isCancelled)
        assertTrue(requireNotNull(callbackJob).isActive)
    }

    suspend fun finish(): Pair<Int?, Throwable?> {
        releaseSecond.complete(Unit)
        val result = runCatching { requireNotNull(caller).await() }
        assertTrue(queries.works.isNotEmpty())
        queries.works.forEach { work ->
            assertFinite(work.context.job)
            work.context.job.join()
            work.returned.await()
            assertTrue(work.returned.isCompleted)
            assertEquals(1, work.forwardCount.get())
        }
        secondWritten.await().also(::assertFinite).join()
        assertTrue(requireNotNull(original).isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
        assertEquals(1, minted.size)
        return result.getOrNull() to result.exceptionOrNull()
    }

    suspend fun successfulSnapshot(
        before: Pair<JsonObject, List<PendingSendEntity>>,
    ): Pair<JsonObject, List<PendingSendEntity>> {
        val existing = target.copy(
            name = SECOND_NAME, typeRawValue = ContactType.CHAT.raw, flags = COMBINED_FLAGS,
            outPathLength = 0, outPath = ByteArray(0), lastAdvertTimestamp = 0, latitude = 0.0, longitude = 0.0,
            lastModified = SECOND_TIME,
        )
        val newRow = ContactDto(
            id = minted.single(), radioID = target.radioID, publicKey = insertedFrame.publicKey,
            name = REPEATED_NAME, typeRawValue = ContactType.CHAT.raw, flags = COMBINED_FLAGS,
            outPathLength = 0, outPath = ByteArray(0), lastAdvertTimestamp = 0,
            latitude = 0.0, longitude = 0.0, lastModified = REPEAT_TIME,
            isBlocked = false, isMuted = false, isFavorite = true, isPinned = false, unreadCount = 0,
        )
        val contacts = (
            initialContacts.map {
                if (it.id ==
                    target.id
                ) {
                    existing
                } else {
                    it
                }
            } + newRow
            ).sortedBy { it.id.value }
        return JsonObject(before.first.toMutableMap().apply { put("contacts", JSON.encodeToJsonElement(contacts)) }) to
            before.second
    }

    private suspend fun observeSecondWrite() {
        val inserted = database.contactDao().getById(minted.single())
        val updated = database.contactDao().getById(rows.firstContact)
        assertEquals(FIRST_NAME, inserted?.name)
        assertArrayEquals(insertedFrame.publicKey, inserted?.publicKey)
        assertEquals(target.radioID, inserted?.radioID)
        assertEquals(SECOND_NAME, updated?.name)
        assertEquals(COMBINED_FLAGS, updated?.flags)
        assertTrue(requireNotNull(original).isActive)
        callbackJob = currentCoroutineContext().job
        secondWritten.complete(requireNotNull(callbackJob))
        if (fault != null) throw fault
        releaseSecond.await()
    }

    private fun assertFinite(job: Job) {
        val room = database.javaClass.getMethod("getCoroutineScope").invoke(database) as CoroutineScope
        assertNotSame(room.coroutineContext.job, job)
        assertNotSame(scope.coroutineContext.job, job)
        assertNotSame(external, job)
        assertNotSame(original, job)
    }

    fun record(name: String, expectedEqual: Boolean, survived: Boolean, errorMatched: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "contact-frame-batch")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.json").writeText(
            JSONObject().put("expectedSnapshotEqual", expectedEqual).put("survivorsEqual", survived)
                .put("expectedOutcome", errorMatched).put("realInsertAndUpdateObserved", secondWritten.isCompleted)
                .put("qualifiedFiniteWorkCount", queries.works.size).put(
                    "allWorkersCompleted",
                    queries.works.all {
                        it.context.job.isCompleted && it.returned.isCompleted && it.forwardCount.get() == 1
                    },
                ).put("originCompleted", requireNotNull(original).isCompleted).put("mintCount", minted.size)
                .put("radioOrSessionConstructed", false).toString(),
        )
    }

    fun close(name: String) {
        releaseSecond.complete(Unit)
        try {
            runBlocking(dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    caller?.cancelAndJoin()
                    queries.works.forEach {
                        assertFinite(it.context.job)
                        it.context.job.join()
                        it.returned.await()
                    }
                    if (secondWritten.isCompleted) secondWritten.await().also(::assertFinite).join()
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

    private class RecordingDispatcher : CoroutineDispatcher() {
        val works = CopyOnWriteArrayList<Work>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val frames = Thread.currentThread().stackTrace.map { it.className + "." + it.methodName }
            if (context[CoroutineName]?.name == ORIGIN && frames.any(::isStoreWriter) && frames.any(::isOuterWriter)) {
                val work = Work(context)
                works.add(work)
                check(work.forwardCount.incrementAndGet() == 1)
                Dispatchers.IO.dispatch(context) {
                    try {
                        block.run()
                    } finally {
                        work.returned.complete(Unit)
                    }
                }
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }
        private fun isStoreWriter(frame: String): Boolean =
            frame.startsWith("one.meshcore.shared.persistence.RoomContactStore.") ||
                frame.startsWith("one.meshcore.shared.persistence.RoomContactStore$")

        private fun isOuterWriter(frame: String): Boolean =
            frame == "androidx.room.RoomDatabaseKt.useWriterConnection" ||
                (frame.startsWith("androidx.room.util.DBUtil") && frame.endsWith(".performInTransactionSuspending"))

        data class Work(
            val context: CoroutineContext,
            val returned: CompletableDeferred<Unit> = CompletableDeferred(),
            val forwardCount: AtomicInteger = AtomicInteger(),
        )
    }

    companion object {
        private const val ORIGIN = "contact-frame-native-origin"
        private const val KEY_BYTES = 32
        const val TIMEOUT_MS = 10_000L
        const val FRAME_COUNT = 3
        const val REPEAT_TIME = 1_700_000_300L
        private const val SECOND_WRITE = 2
        private const val NEW_KEY_BYTE = 0x77
        private const val FAVORITE_FLAG = 1
        private const val NON_FAVORITE_FLAG = 2
        private const val COMBINED_FLAGS = 3
        private const val FIRST_TIME = 1_700_000_100L
        private const val SECOND_TIME = 1_700_000_200L
        private const val FIRST_NAME = "Inserted first"
        private const val SECOND_NAME = "Updated second"
        private const val REPEATED_NAME = "Repeated new key"
        private val JSON = Json { encodeDefaults = true }
    }
}
