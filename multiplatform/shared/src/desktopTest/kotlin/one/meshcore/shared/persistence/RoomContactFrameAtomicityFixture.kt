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
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ContactSyncResult
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.contactFrame
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactFrameAtomicityFixture(private val fault: Throwable?) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactFrameWriterDispatcher()
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val minted = CopyOnWriteArrayList<Uuid>()
    private val secondWritten = CompletableDeferred<Job>()
    private val releaseSecond = CompletableDeferred<Unit>()
    private var writes = 0
    private var callbackJob: Job? = null
    private var external: Job? = null
    private var original: Job? = null
    private var caller: Deferred<ContactSyncResult>? = null
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
            assertNotSame(public, original)
            assertTrue(public.children.any { it === original })
            return actualStore.batchSaveContacts(radioID, frames)
        }
    }
    private val session = FakeContactSession()
    private val service = testContactService(session = session, store = observedStore)
    private val insertedFrame = contactFrame(
        NEW_KEY_BYTE,
        FIRST_NAME,
    ).copy(flags = FAVORITE_FLAG, lastModified = FIRST_TIME)

    suspend fun seed() {
        rows.seed()
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(flags = FAVORITE_FLAG, isFavorite = true, isPinned = true, nickname = "Local"),
        )
        initialContacts = database.contactDao().listAll().map { it.toDto() }
        target = initialContacts.single { it.id == rows.firstContact }
        val update = contactFrame(NEW_KEY_BYTE, SECOND_NAME).copy(
            publicKey = rows.firstKey.copyOf(),
            flags = NON_FAVORITE_FLAG,
            lastModified = SECOND_TIME,
        )
        val repeated = insertedFrame.copy(name = REPEATED_NAME, flags = NON_FAVORITE_FLAG, lastModified = REPEAT_TIME)
        session.stubbedContacts = listOf(insertedFrame, update, repeated).map { it.toMeshContact() }
        assertTrue(database.contactDao().listAll().none { it.publicKey.contentEquals(insertedFrame.publicKey) })
    }

    suspend fun start() {
        queries.arm(NO_HELD_DISPATCH)
        caller = scope.async(CoroutineName(QueuedContactFrameWriterDispatcher.ORIGIN)) {
            external = currentCoroutineContext().job
            service.syncContacts(target.radioID, since = SINCE)
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

    suspend fun finish(): Pair<ContactSyncResult?, Throwable?> {
        releaseSecond.complete(Unit)
        queries.release()
        val result = runCatching { requireNotNull(caller).await() }
        queries.works.forEach { work ->
            assertFinite(work.context.job)
            work.context.job.join()
            work.returned.await()
            assertTrue(work.ran.get())
            assertEquals(1, work.forwardCount.get())
        }
        secondWritten.await().also(::assertFinite).join()
        assertTrue(requireNotNull(original).isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive && !service.isSyncingContacts)
        assertEquals(1, minted.size)
        assertTrue(session.addedContacts.isEmpty() && session.removedPublicKeys.isEmpty())
        assertTrue(session.changeFlagsCalls.isEmpty() && session.resetPathPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty() && session.getContactKeys.isEmpty())
        assertTrue(session.pathDiscoveryDestinations.isEmpty())
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
        val newRow = contactDto(
            id = minted.single(),
            radioID = target.radioID,
            keyByte = NEW_KEY_BYTE,
            name = REPEATED_NAME,
            flags = COMBINED_FLAGS,
            isFavorite = true,
        ).copy(lastModified = REPEAT_TIME)
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
        assertContentEquals(insertedFrame.publicKey, inserted?.publicKey)
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

    fun close() {
        releaseSecond.complete(Unit)
        queries.release()
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
                    rows.close()
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val FRAME_COUNT = 3
        const val REPEAT_TIME = 1_700_000_300L
        private const val NO_HELD_DISPATCH = Int.MAX_VALUE
        private const val SECOND_WRITE = 2
        private const val NEW_KEY_BYTE = 0x77
        private const val FAVORITE_FLAG = 1
        private const val NON_FAVORITE_FLAG = 2
        private const val COMBINED_FLAGS = 3
        private const val FIRST_TIME = 1_700_000_100L
        private const val SECOND_TIME = 1_700_000_200L
        private const val SINCE = 1_699_999_000L
        private const val FIRST_NAME = "Inserted first"
        private const val SECOND_NAME = "Updated second"
        private const val REPEATED_NAME = "Repeated new key"
        private val JSON = Json { encodeDefaults = true }
    }
}
