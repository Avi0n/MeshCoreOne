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
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.updateContactAvatar
import one.meshcore.shared.contact.updateContactPreferences
import one.meshcore.shared.model.Uuid
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

internal class RoomContactPreferenceWriterFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedContactPreferenceWriterDispatcher()
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
    private val originEntered = CompletableDeferred<Job>()
    private var caller: Deferred<Unit>? = null
    private var original: Job? = null
    private var finiteWriter: Job? = null

    suspend fun seed() {
        rows.seed()
        val row = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            row.copy(
                nickname = OLD_NICKNAME,
                avatarImageData = OLD_AVATAR.copyOf(),
                isPinned = true,
                isMuted = true,
                unreadCount = OLD_UNREAD,
                unreadMentionCount = OLD_MENTIONS,
                lastHeardTimestamp = OLD_HEARD,
                ocvPreset = "fixture curve",
                customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        assertEffects(0)
    }

    suspend fun startNickname() {
        queries.arm()
        caller = scope.async(CoroutineName(QueuedContactPreferenceWriterDispatcher.ORIGIN)) {
            originEntered.complete(currentCoroutineContext().job)
            service.updateContactPreferences(rows.firstContact, nickname = INPUT_NICKNAME)
        }
        original = originEntered.await()
        assertTrue(original === caller)
        val work = queries.entered.await()
        finiteWriter = finiteRoomOperation(work, requireNotNull(original))
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(work.ran.get())
        assertTrue(work.frames.any(QueuedContactPreferenceWriterDispatcher::isStoreWriter))
        assertTrue(work.frames.any(QueuedContactPreferenceWriterDispatcher::isOuterWriter))
        assertEquals(QueuedContactPreferenceWriterDispatcher.ORIGIN, work.context[CoroutineName]?.name)
        assertEquals(OLD_NICKNAME, requireNotNull(database.contactDao().getById(rows.firstContact)).nickname)
        assertEffects(0)
    }

    suspend fun editAvatar() {
        service.updateContactAvatar(rows.firstContact, FRESH_AVATAR.copyOf())
        val committed = requireNotNull(database.contactDao().getById(rows.firstContact))
        assertContentEquals(FRESH_AVATAR, committed.avatarImageData)
        assertEquals(OLD_NICKNAME, committed.nickname)
        assertTrue(requireNotNull(original).isActive && requireNotNull(finiteWriter).isActive)
        assertFalse(queries.entered.await().ran.get())
        assertEffects(1)
    }

    suspend fun expectedNicknameSnapshot(): Pair<JsonObject, List<PendingSendEntity>> {
        val before = rows.snapshot()
        val contacts = database.contactDao().listAll().sortedBy { it.id.value }
            .map { if (it.id == rows.firstContact) it.copy(nickname = EXPECTED_NICKNAME).toDto() else it.toDto() }
        return JsonObject(before.first.toMutableMap().apply { put("contacts", json.encodeToJsonElement(contacts)) }) to
            before.second
    }

    suspend fun releaseAndJoin() {
        queries.release()
        requireNotNull(caller).await()
        requireNotNull(finiteWriter).join()
        val work = queries.entered.await()
        work.returned.await()
        assertTrue(work.ran.get() && requireNotNull(finiteWriter).isCompleted && requireNotNull(caller).isCompleted)
        assertTrue(scope.coroutineContext.job.isActive)
    }

    suspend fun missingTarget() {
        val missing = Uuid.random()
        assertTrue(database.contactDao().getById(missing) == null)
        val missingCaller = scope.async { service.updateContactPreferences(missing, nickname = INPUT_NICKNAME) }
        assertIs<ContactServiceError.ContactNotFound>(runCatching { missingCaller.await() }.exceptionOrNull())
        assertTrue(missingCaller.isCompleted)
        assertFalse(queries.entered.isCompleted)
        assertEffects(0)
    }

    fun assertEffects(expectedNotifications: Int) {
        assertEquals(expectedNotifications, notifications.contactsChanged)
        assertEquals(0, notifications.conversationsChanged)
        assertTrue(cleanup.invocations.isEmpty())
        assertTrue(session.addedContacts.isEmpty())
        assertTrue(session.removedPublicKeys.isEmpty())
        assertTrue(session.resetPathPublicKeys.isEmpty())
        assertTrue(session.sharedPublicKeys.isEmpty())
        assertTrue(session.changeFlagsCalls.isEmpty())
        assertTrue(session.pathDiscoveryDestinations.isEmpty())
        assertTrue(session.getContactKeys.isEmpty())
    }

    private fun finiteRoomOperation(work: QueuedContactPreferenceWriterDispatcher.Work, origin: Job): Job {
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
        private const val INPUT_NICKNAME = "  Updated nickname  "
        private const val EXPECTED_NICKNAME = "Updated nickname"
        private const val OLD_UNREAD = 7
        private const val OLD_MENTIONS = 2
        private const val OLD_HEARD = 1_700_000_000L
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
    }
}
