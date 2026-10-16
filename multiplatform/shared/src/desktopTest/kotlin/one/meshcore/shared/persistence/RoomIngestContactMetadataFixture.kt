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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import one.meshcore.shared.chat.BlockedSendersCache
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.DeliveryContext
import one.meshcore.shared.chat.IncomingMessage
import one.meshcore.shared.chat.IncomingMessageBroadcaster
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.IngestStore
import one.meshcore.shared.chat.MessageIngestCollaborators
import one.meshcore.shared.chat.MessageIngestEdges
import one.meshcore.shared.chat.MessageIngestPipeline
import one.meshcore.shared.chat.contactMessage
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

internal class RoomIngestContactMetadataFixture {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queries = QueuedChatContactMetadataWriterDispatcher()
    val rows = RoomContactDeleteAtomicityFixture { path ->
        Room.databaseBuilder<MeshDatabase>(name = path).setQueryCoroutineContext(queries)
            .setDriver(BundledSQLiteDriver()).productionSchemaPolicy().build()
    }
    private val database get() = rows.database
    private val senderPrefix get() = rows.firstKey.copyOf(PREFIX_BYTES)
    private val session = FakeContactSession()
    private val cleanup = RecordingCleanupHandler()
    private val notifications = RecordingContactSyncCoordinator()
    private val originalEntered = CompletableDeferred<Job>()
    private var publicJob: Job? = null
    private var originalJob: Job? = null
    val discovered = RoomDiscoveredNodeStore(database.discoveredNodeDao())
    private val broadcaster = IncomingMessageBroadcaster()
    private val notifier = ContactMetadataNoticeRecorder()
    private var events: ReceiveChannel<IncomingMessageEvent>? = null
    private var expectedRows: Pair<JsonObject, List<PendingSendEntity>>? = null
    private var expectedDiscover: List<one.meshcore.shared.contact.DiscoveredNodeRecord> = emptyList()
    private var resolved: one.meshcore.shared.chat.ResolvedContact? = null
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
    private val observed = object : IngestStore by chatStore {
        override suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long) {
            capture(currentCoroutineContext().job)
            chatStore.touchContactHeard(contactID, atEpochSeconds)
        }
    }
    private val pipeline = MessageIngestPipeline(
        MessageIngestCollaborators(
            radioID = ChatFixtures.RADIO,
            store = observed,
            blockedSenders = BlockedSendersCache(),
            broadcaster = broadcaster,
            selfNodeName = { "Me" },
            now = { ContactMetadataExpected.NOW },
            newMessageId = { ContactMetadataExpected.MESSAGE_ID },
        ),
        MessageIngestEdges(notifier = notifier),
    )
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
        val old = requireNotNull(database.contactDao().getById(rows.firstContact))
        database.contactDao().upsert(
            old.copy(
                nickname = "Local nickname", avatarImageData = OLD_AVATAR.copyOf(), flags = SEED_FLAGS,
                isFavorite = false, isPinned = true, isMuted = true, isBlocked = false,
                unreadCount = 7, unreadMentionCount = 2,
                lastHeardTimestamp = OLD_HEARD, ocvPreset = "fixture curve", customOCVArrayString = "[3.4,3.8,4.2]",
            ),
        )
        val contact = requireNotNull(database.contactDao().getById(rows.firstContact))
        resolved =
            requireNotNull(chatStore.resolveContact(ChatFixtures.RADIO, senderPrefix))
        assertEquals(rows.firstContact, requireNotNull(resolved).id)
        discovered.upsert(ContactMetadataExpected.discovered(contact, OLD_HEARD))
        events = broadcaster.subscribe()
        assertEffects(edited = false)
    }

    suspend fun start() {
        queries.arm(FIRST_WRITER)
        caller = scope.async(CoroutineName(QueuedChatContactMetadataWriterDispatcher.ORIGIN)) {
            publicJob = currentCoroutineContext().job
            pipeline.ingest(
                IncomingMessage.Direct(
                    contactMessage(
                        text = ContactMetadataExpected.BODY,
                        senderTimestamp = ContactMetadataExpected.SENDER_TIME,
                        senderPrefix = senderPrefix,
                    ),
                    requireNotNull(resolved),
                ),
                DeliveryContext.Live,
            )
        }
        val original = originalEntered.await()
        held = queries.entered.await()
        val work = requireNotNull(held)
        assertTrue(publicJob === caller && original === caller)
        assertTrue(original.isActive && finiteOperation(work).isActive)
        assertFalse(work.ran.get())
        assertEquals(1, queries.works.size)
        assertTrue(work.frames.any { it.contains("RoomChatBookkeeping") && it.contains("touchContactHeard") })
        assertEffects(edited = false)
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

    suspend fun prepareExpected() {
        val before = rows.snapshot()
        val contacts = database.contactDao().listAll().sortedBy { it.id.value }.map { row ->
            if (row.id == rows.firstContact) {
                row.copy(
                    lastHeardTimestamp = ContactMetadataExpected.NOW.toLong(),
                    lastMessageDate = ContactMetadataExpected.NOW,
                    unreadCount = row.unreadCount + 1,
                    unreadMentionCount = row.unreadMentionCount + 1,
                ).toDto()
            } else {
                row.toDto()
            }
        }
        val message = ContactMetadataExpected.message(rows.firstContact, senderPrefix)
        val messages = (database.messageDao().listAll().map { it.toDto() } + message).sortedBy { it.id.value }
        expectedRows = JsonObject(
            before.first.toMutableMap().apply {
                put("contacts", JSON.encodeToJsonElement(contacts))
                put("messages", JSON.encodeToJsonElement(messages))
            },
        ) to before.second
        expectedDiscover = discovered.snapshot.value.map {
            if (it.publicKey.contentEquals(rows.firstKey) && it.radioID == ChatFixtures.RADIO) {
                it.copy(lastHeardEpochSeconds = ContactMetadataExpected.NOW.toLong())
            } else {
                it
            }
        }
    }

    fun expectedSnapshot(): Pair<JsonObject, List<PendingSendEntity>> = requireNotNull(expectedRows)

    suspend fun finish(edited: Boolean) {
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
        assertTrue(result.isSuccess)
        assertEquals(Unit, result.getOrNull())
        assertTrue(queries.works.size >= MINIMUM_CONTACT_WRITERS)
        val message = ContactMetadataExpected.message(rows.firstContact, senderPrefix)
        assertEquals(JSON.encodeToJsonElement(message), JSON.encodeToJsonElement(chatStore.fetchMessage(message.id)))
        val received = requireNotNull(events).tryReceive().getOrThrow()
        assertTrue(received is IncomingMessageEvent.DirectMessageReceived)
        assertEquals(rows.firstContact, received.contactID)
        assertEquals(JSON.encodeToJsonElement(message), JSON.encodeToJsonElement(received.message))
        assertTrue(requireNotNull(events).tryReceive().isFailure)
        assertEquals(expectedDiscover, database.discoveredNodeDao().listAll().map { it.toRecord() })
        assertEquals(expectedDiscover, discovered.snapshot.value)
        notifier.assertReceived(requireNotNull(resolved), message)
        ContactMetadataExpected.assertBookkeeping(requireNotNull(database.contactDao().getById(rows.firstContact)))
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
                    events?.cancel()
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
        private const val OLD_HEARD = 1_700_000_000L
        private const val PREFIX_BYTES = 6
        private const val MINIMUM_CONTACT_WRITERS = 4
        private val EMPTY = ByteArray(0)
        private val OLD_AVATAR = byteArrayOf(3, 4)
        private val FRESH_AVATAR = byteArrayOf(21, 22, 23)
        private val JSON = Json { encodeDefaults = true }
    }
}
