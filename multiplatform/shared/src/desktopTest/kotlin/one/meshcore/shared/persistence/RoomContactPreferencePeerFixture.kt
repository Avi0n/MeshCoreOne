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
import one.meshcore.shared.contact.ContactCleanupReason
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactPreferencePeerFixture(val kind: RoomContactPreferencePeerCase) {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactPreferencePeerWriterDispatcher()
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val json = Json { encodeDefaults = true }
    private val session = FakeContactSession()
    private val cleanup = RecordingCleanupHandler()
    private val notifications = RecordingContactSyncCoordinator()
    private val service = testContactService(
        session = session,
        store = rows.store,
        cleanup = cleanup,
        syncCoordinator = notifications,
    )
    val cleanupCount: Int get() = cleanup.invocations.size
    val contactNotificationCount: Int get() = notifications.contactsChanged
    private val originEntered = CompletableDeferred<Job>()
    private var caller: Deferred<Unit>? = null
    private var original: Job? = null
    private var finiteWriter: Job? = null
    private var commandContact: MeshContact? = null

    suspend fun seed() {
        rows.seed()
        val row = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            row.copy(
                nickname = OLD_NICKNAME,
                avatarImageData = OLD_AVATAR.copyOf(),
                flags = RoomContactPreferencePeerCase.BASE_FLAGS,
                isFavorite = false,
                isBlocked = false,
                isPinned = true,
                isMuted = true,
                unreadCount = OLD_UNREAD,
                unreadMentionCount = OLD_MENTIONS,
                lastHeardTimestamp = OLD_HEARD,
                ocvPreset = "fixture curve",
                customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        assertEffects(0, 0, commands = 0)
    }

    suspend fun start() {
        commandContact = requireNotNull(database.contactDao().getById(rows.firstContact)).toDto().toMeshContact()
        queries.arm()
        caller = scope.async(CoroutineName(QueuedContactPreferencePeerWriterDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            kind.invoke(service, rows.firstContact)
        }
        original = originEntered.await()
        assertTrue(original === caller)
        val work = queries.entered.await()
        finiteWriter = finiteRoomOperation(work, requireNotNull(original))
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(work.ran.get())
        assertTrue(work.frames.any(QueuedContactPreferencePeerWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedContactPreferencePeerWriterDispatcher::isOuterWriter))
        assertEquals(QueuedContactPreferencePeerWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertEquals(OLD_NICKNAME, requireNotNull(database.contactDao().getById(rows.firstContact)).nickname)
        assertEffects(0, 0)
    }

    suspend fun editCurrent() {
        if (kind == RoomContactPreferencePeerCase.AVATAR) {
            service.updateContactPreferences(rows.firstContact, nickname = FRESH_NICKNAME)
            assertEquals(FRESH_NICKNAME, requireNotNull(database.contactDao().getById(rows.firstContact)).nickname)
        } else {
            service.updateContactAvatar(rows.firstContact, FRESH_AVATAR.copyOf())
            assertContentEquals(
                FRESH_AVATAR,
                requireNotNull(database.contactDao().getById(rows.firstContact)).avatarImageData,
            )
        }
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(queries.entered.await().ran.get())
        assertEffects(1, 0)
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

    suspend fun deleteAfterCommand() {
        assertTrue(kind.isCommand)
        assertEffects(0, 0)
        rows.store.deleteContact(rows.firstContact)
        assertTrue(database.contactDao().getById(rows.firstContact) == null)
        assertEquals(0, rows.childRowCount(rows.firstContact))
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(queries.entered.await().ran.get())
    }

    suspend fun releaseAndJoin(): Throwable? {
        queries.release()
        val cause = runCatching { requireNotNull(caller).await() }.exceptionOrNull()
        requireNotNull(finiteWriter).join()
        val work = queries.entered.await()
        work.returned.await()
        assertTrue(work.ran.get() && requireNotNull(finiteWriter).isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(cause == null || cause is CancellationException || cause is ContactServiceError.ContactNotFound)
        assertTrue(scope.coroutineContext.job.isActive)
        return cause
    }

    suspend fun missingTarget() {
        val missing = Uuid.random()
        assertTrue(database.contactDao().getById(missing) == null)
        val missingCaller = scope.async { kind.invoke(service, missing) }
        assertIs<ContactServiceError.ContactNotFound>(runCatching { missingCaller.await() }.exceptionOrNull())
        assertTrue(missingCaller.isCompleted)
        assertFalse(queries.entered.isCompleted)
        assertEffects(0, 0, commands = 0)
    }

    fun assertEffects(expectedNotifications: Int, expectedCleanup: Int, commands: Int = if (kind.isCommand) 1 else 0) {
        assertEquals(expectedNotifications, notifications.contactsChanged)
        assertEquals(0, notifications.conversationsChanged)
        assertEquals(expectedCleanup, cleanup.invocations.size)
        cleanup.invocations.forEach {
            assertEquals(rows.firstContact, it.contactID)
            assertEquals(ContactCleanupReason.BLOCKED, it.reason)
            assertContentEquals(rows.firstKey, it.publicKey)
        }
        assertEquals(commands, session.changeFlagsCalls.size)
        if (commands != 0) {
            val recorded = session.changeFlagsCalls.single()
            assertEquals(normalizedContact(requireNotNull(commandContact)), normalizedContact(recorded.first))
            assertEquals(kind.commandFlags, recorded.second)
        }
        assertTrue(session.addedContacts.isEmpty())
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(session.resetPathPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty())
        assertTrue(session.pathDiscoveryDestinations.isEmpty())
        assertTrue(session.getContactKeys.isEmpty())
    }

    private fun normalizedContact(contact: MeshContact): List<Any> = listOf(
        contact.copy(publicKey = EMPTY, outPath = EMPTY),
        contact.publicKey.toList(),
        contact.outPath.toList(),
    )

    private fun finiteRoomOperation(work: QueuedContactPreferencePeerWriterDispatcher.Work, origin: Job): Job {
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
        private const val OLD_NICKNAME = "Original nickname"
        private const val FRESH_NICKNAME = "Concurrent nickname"
        private const val OLD_UNREAD = 7
        private const val OLD_MENTIONS = 2
        private const val OLD_HEARD = 1_700_000_000L
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
        private val EMPTY = ByteArray(0)
    }
}
