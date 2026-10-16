package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChatDaos
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toDtoOrNull
import one.meshcore.shared.persistence.toEntity
import one.meshcore.shared.simulator.MockDataProvider
import java.io.File
import java.util.concurrent.Executors

internal class ChatSendQueueRetirementFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val dbFile = File.createTempFile("queue-retirement", ".db").also { it.delete() }
    private val database = meshDatabase(dbFile.absolutePath)
    private val ids = SequentialIds()
    private val gates = mutableListOf<ChatSendQueueRetirementStore.Gate>()
    private val primary = graph(controlled = true)
    val queue: ChatSendQueueService get() = primary.queue
    val store: ChatSendQueueRetirementStore get() = primary.controlledStore!!
    lateinit var message: MessageDto
        private set
    private var channel = false
    private lateinit var foreignMessage: MessageDto
    private lateinit var foreignPending: PendingSendDto

    suspend fun initialize(isChannel: Boolean, retry: Boolean = false, sent: Boolean = false) = onThread {
        channel = isChannel
        val device = MockDataProvider.simulatorDevice(TEST_NOW_SECONDS)
        database.deviceDao().upsert(device.copy(id = DEVICE_A, radioID = RADIO_A))
        database.deviceDao().upsert(device.copy(id = DEVICE_B, radioID = RADIO_B))
        database.contactDao().upsert(ChatFixtures.contact("Fixture contact", id = ChatFixtures.CONTACT).toEntity())
        database.channelDao().upsert(
            ChannelDto(
                CHANNEL_A,
                RADIO_A,
                CHANNEL_INDEX,
                "Fixture channel",
                ByteArray(SECRET_SIZE),
                true,
                0,
            ).toEntity(),
        )
        val status = when {
            sent -> MessageStatus.SENT
            retry -> MessageStatus.FAILED
            else -> MessageStatus.PENDING
        }
        message = ChatFixtures.message(
            text = TEST_TEXT,
            status = status,
            contactID = ChatFixtures.CONTACT.takeUnless { channel },
            channelIndex = CHANNEL_INDEX.takeIf { channel },
        )
        primary.messages.saveMessage(message)
        message = primary.messages.fetchMessage(message.id)!!
        if (retry) primary.persisted.upsertPendingSend(pending(message, attempts = PARKED_ATTEMPTS))
        foreignMessage = message.copy(id = FOREIGN_MESSAGE, radioID = RADIO_B, status = MessageStatus.SENT)
        database.messageDao().upsert(foreignMessage.toEntity())
        foreignMessage = database.messageDao().getById(FOREIGN_MESSAGE)!!.toDto()
        foreignPending = pending(foreignMessage)
        primary.persisted.upsertPendingSend(foreignPending)
    }

    suspend fun holdWrite(afterCommit: Boolean): ChatSendQueueRetirementStore.WriteGate = onThread {
        ChatSendQueueRetirementStore.WriteGate(afterCommit).also {
            gates += it
            store.nextWrite = it
        }
    }

    suspend fun holdHydrate(): ChatSendQueueRetirementStore.Gate = onThread {
        ChatSendQueueRetirementStore.Gate().also {
            gates += it
            store.nextFetch = it
        }
    }

    suspend fun holdWarmUp(): ChatSendQueueRetirementStore.Gate = onThread {
        ChatSendQueueRetirementStore.Gate().also {
            gates += it
            store.nextPurge = it
        }
    }

    suspend fun start(block: suspend () -> Unit): Deferred<Unit> = onThread {
        scope.async(start = CoroutineStart.UNDISPATCHED) { block() }
    }

    suspend fun produce(retry: Boolean = false, resend: Boolean = false) = onThread {
        if (channel) {
            val envelope = channelEnvelope(resend)
            if (retry) queue.retryChannel(envelope) else queue.enqueueChannel(envelope)
        } else {
            val envelope = DirectMessageEnvelope(message.id, ChatFixtures.CONTACT, resend)
            if (retry) queue.retryDM(envelope) else queue.enqueueDM(envelope)
        }
    }

    suspend fun seedPending() = onThread { primary.persisted.upsertPendingSend(pending(message)) }

    suspend fun flush() = onThread { yield() }

    suspend fun awaitOldDrain() = onThread { queue.awaitDrainCompletion() }

    suspend fun replayFresh(): Int = onThread {
        val fresh = graph(controlled = false)
        fresh.queue.hydrate()
        fresh.queue.awaitDrainCompletion()
        fresh.queue.hydrate()
        fresh.queue.awaitDrainCompletion()
        fresh.session.directSendAttempts.size + fresh.session.channelSends.size
    }

    suspend fun snapshot(label: String): Snapshot = onThread {
        val rows = database.pendingSendDao().listByRadio(RADIO_A).mapNotNull { it.toDtoOrNull() }
        val result = Snapshot(
            message = database.messageDao().getById(message.id)!!.toDto(),
            pending = rows,
            foreignMessage = database.messageDao().getById(FOREIGN_MESSAGE)!!.toDto(),
            foreignPending = database.pendingSendDao().listByRadio(RADIO_B).mapNotNull { it.toDtoOrNull() },
            oldWrites = primary.session.directSendAttempts.size + primary.session.channelSends.size,
            writeCalls = store.writeCalls,
        )
        println(
            "queueRetirement=$label message=${result.message.id}/${result.message.status} " +
                "pending=${rows.map { it.messageID to it.radioID }} oldWrites=${result.oldWrites} " +
                "writeCalls=${result.writeCalls}",
        )
        result
    }

    fun foreignBaseline(): Pair<MessageDto, List<PendingSendDto>> = foreignMessage to listOf(foreignPending)

    suspend fun close() {
        try {
            gates.forEach { it.release.complete(Unit) }
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    private fun graph(controlled: Boolean): Graph {
        val messages = RoomChatStore(
            RADIO_A,
            ChatDaos(
                database.messageDao(),
                database.contactDao(),
                database.channelDao(),
                database.messageRepeatDao(),
                database.pendingSendDao(),
                database.reactionDao(),
            ),
            RxLogCorrelator(database.rxLogEntryDao()),
        )
        val persisted = DefaultSendQueueStore(RoomPendingSendStore(database.pendingSendDao()), messages) { id ->
            database.contactDao().getById(id)?.toDto()
        }
        val held = if (controlled) ChatSendQueueRetirementStore(persisted) else null
        val session = FakeChatSession().apply { ackArrives = true }
        val service = testMessageService(session, messages, scope, ids = ids)
        val queue = ChatSendQueueService(
            RADIO_A,
            ChatSendQueueCollaborators(held ?: persisted, service, { true }, scope, { TEST_NOW_SECONDS }, ids::next),
        )
        return Graph(messages, persisted, held, session, queue)
    }

    private fun channelEnvelope(resend: Boolean) =
        ChannelMessageEnvelope(message.id, CHANNEL_INDEX, resend, message.text, message.timestamp)

    private fun pending(row: MessageDto, attempts: Int = 0): PendingSendDto = if (channel) {
        ChannelMessageEnvelope(row.id, CHANNEL_INDEX, messageText = row.text, messageTimestamp = row.timestamp)
            .toPendingSendDto(ids.next(), row.radioID, 1, TEST_NOW_SECONDS, attempts)
    } else {
        DirectMessageEnvelope(row.id, ChatFixtures.CONTACT)
            .toPendingSendDto(ids.next(), row.radioID, 1, TEST_NOW_SECONDS, attempts)
    }

    private suspend fun <T> onThread(block: suspend () -> T): T = withContext(dispatcher) { block() }

    class Snapshot(
        val message: MessageDto,
        val pending: List<PendingSendDto>,
        val foreignMessage: MessageDto,
        val foreignPending: List<PendingSendDto>,
        val oldWrites: Int,
        val writeCalls: Int,
    )

    private class Graph(
        val messages: RoomChatStore,
        val persisted: SendQueueStore,
        val controlledStore: ChatSendQueueRetirementStore?,
        val session: FakeChatSession,
        val queue: ChatSendQueueService,
    )

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val TEST_TEXT = "queue retirement fixture"
        const val CHANNEL_INDEX = 1
        private const val SECRET_SIZE = 16
        private const val PARKED_ATTEMPTS = 4
        val RADIO_A = ChatFixtures.RADIO
        val RADIO_B = Uuid.normalized("C0000000-0000-4000-8000-00000000000B")
        private val DEVICE_A = Uuid.normalized("D0000000-0000-4000-8000-00000000000A")
        private val DEVICE_B = Uuid.normalized("D0000000-0000-4000-8000-00000000000B")
        private val CHANNEL_A = Uuid.normalized("E0000000-0000-4000-8000-00000000000A")
        private val FOREIGN_MESSAGE = Uuid.normalized("F0000000-0000-4000-8000-00000000000B")
    }
}
