package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.ChatDaos
import one.meshcore.shared.persistence.PendingSendEntity
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity
import java.io.File
import java.util.concurrent.Executors

internal class ChatThreadRadioAdmissionFixture {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val dbFile = File.createTempFile("chat-radio-admission", ".db").also { it.delete() }
    private val database = meshDatabase(dbFile.absolutePath)
    private val drainRelease = CompletableDeferred<Unit>()
    private val ids = SequentialIds()
    private val contact = ChatFixtures.contact(name = "Fixture contact", id = ChatFixtures.CONTACT)
    private val graphs = mutableListOf<Graph>()
    private var nextCommit: Commit? = null
    private var persistFailure: CompletableDeferred<Unit>? = null
    private val first = graph(RADIO_A)
    private val second = graph(RADIO_B)
    private var live = second
    private var channel = false
    lateinit var viewModel: ChatThreadViewModel
        private set

    suspend fun configure(isChannel: Boolean, matchingRadio: Boolean = false) = onThread {
        channel = isChannel
        live = if (matchingRadio) first else second
        database.contactDao().upsert(contact.toEntity())
        database.channelDao().upsert(channel(RADIO_A, CHANNEL_A).toEntity())
        database.channelDao().upsert(channel(RADIO_B, CHANNEL_B).toEntity())
        val conversation = if (channel) {
            ChatConversationID.channel(RADIO_A, CHANNEL_INDEX)
        } else {
            ChatConversationID.dm(RADIO_A, contact.id)
        }
        val coordinator = ChatCoordinator(conversation, first.store, scope, computeDispatcher = dispatcher)
        viewModel = ChatThreadViewModel(
            conversationID = conversation,
            coordinator = coordinator,
            scope = scope,
            dependencies = ChatThreadViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { first.store },
                sendQueue = { live.queue },
                messageService = { live.messages },
                currentContact = { contact.takeUnless { channel } },
                unreadCount = { 0 },
                isReady = { true },
                envInputs = { EnvInputs(currentUserName = "Fixture", showIncomingSendTime = false) },
                resolveSender = TEST_SENDER_RESOLVER,
                pendingScrollRequest = { null },
                consumePendingScrollRequest = { false },
                indexPageForReactions = {},
            ),
        )
        viewModel.loadInitial()
    }

    suspend fun send() = onThread { viewModel.sendMessage(TEST_TEXT) }

    suspend fun holdNextMessageCommit(): Commit = onThread {
        check(nextCommit == null)
        Commit().also { nextCommit = it }
    }

    suspend fun beginSend(): Deferred<Unit> = onThread { scope.async { viewModel.sendMessage(TEST_TEXT) } }

    suspend fun replaceLiveOwner(sameRadio: Boolean) = onThread {
        val original = live
        live = if (sameRadio) graph(RADIO_A) else second
        check(original.queue !== live.queue && original.messages !== live.messages)
    }

    suspend fun failNextQueuePersist(): CompletableDeferred<Unit> = onThread {
        CompletableDeferred<Unit>().also { persistFailure = it }
    }

    suspend fun seedFailedMessage(): Uuid = seedMessage(MessageStatus.FAILED)

    suspend fun seedSentMessage(): Uuid = seedMessage(MessageStatus.SENT)

    private suspend fun seedMessage(status: MessageStatus): Uuid = onThread {
        val message = ChatFixtures.message(
            text = TEST_TEXT,
            status = status,
            contactID = contact.id.takeUnless { channel },
            channelIndex = CHANNEL_INDEX.takeIf { channel },
        )
        first.store.saveMessage(message)
        viewModel.loadInitial()
        message.id
    }

    suspend fun retry(messageID: Uuid, isResend: Boolean) = onThread {
        viewModel.retryMessage(messageID, isResend)
    }

    suspend fun snapshot(label: String): Snapshot = onThread {
        val result = Snapshot(
            messages = database.messageDao().listAll().map { it.toDto() },
            pending = database.pendingSendDao().listByRadio(RADIO_A) + database.pendingSendDao().listByRadio(RADIO_B),
            sessionWrites = graphs.sumOf { it.session.directSendAttempts.size + it.session.channelSends.size },
            sendErrorMessage = viewModel.renderState.value.sendErrorMessage,
        )
        println(
            "radioAdmission=$label messages=${result.messages.map { Triple(it.id, it.radioID, it.status) }} " +
                "pending=${result.pending.map { it.messageID to it.radioID }} sessionWrites=${result.sessionWrites} " +
                "sendError=${result.sendErrorMessage}",
        )
        result
    }

    suspend fun close() {
        try {
            onThread { if (::viewModel.isInitialized) viewModel.cleanup() }
            withTimeout(TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        } finally {
            database.close()
            dbFile.delete()
            dispatcher.close()
        }
    }

    private fun graph(radio: Uuid): Graph {
        val store = RoomChatStore(
            radio,
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
        val session = FakeChatSession()
        val committed = object : MessageStore by store {
            override suspend fun saveMessage(message: MessageDto) {
                store.saveMessage(message)
                val held = nextCommit
                nextCommit = null
                if (held != null) {
                    held.savedEntered.complete(message)
                    held.release.await()
                }
            }
        }
        val messages = testMessageService(session, committed, scope, ids = ids)
        val persisted = DefaultSendQueueStore(RoomPendingSendStore(database.pendingSendDao()), store) { id ->
            database.contactDao().getById(id)?.toDto()
        }
        val held = object : SendQueueStore by persisted {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int {
                val failure = persistFailure
                persistFailure = null
                if (failure != null) {
                    failure.complete(Unit)
                    throw IllegalStateException("fixture queue persistence failure")
                }
                return persisted.insertPendingSendAssigningSequence(dto)
            }

            override suspend fun hasPendingSend(messageID: Uuid): Boolean {
                drainRelease.await()
                return persisted.hasPendingSend(messageID)
            }
        }
        val queue = ChatSendQueueService(
            radioID = radio,
            collaborators = ChatSendQueueCollaborators(
                store = held,
                messageService = messages,
                channelResolver = { true },
                scope = scope,
                now = { TEST_NOW_SECONDS },
                newPendingSendId = ids::next,
            ),
        )
        return Graph(store, messages, queue, session).also { graphs += it }
    }

    private suspend fun <T> onThread(block: suspend () -> T): T = withContext(dispatcher) { block() }

    private fun channel(radio: Uuid, id: Uuid) = ChannelDto(
        id = id,
        radioID = radio,
        index = CHANNEL_INDEX,
        name = "Fixture channel",
        secret = ByteArray(CHANNEL_SECRET_LENGTH),
        isEnabled = true,
        unreadCount = 0,
    )

    class Snapshot(
        val messages: List<MessageDto>,
        val pending: List<PendingSendEntity>,
        val sessionWrites: Int,
        val sendErrorMessage: String?,
    )

    class Commit {
        val savedEntered = CompletableDeferred<MessageDto>()
        val release = CompletableDeferred<Unit>()
    }

    private class Graph(
        val store: RoomChatStore,
        val messages: MessageService,
        val queue: ChatSendQueueService,
        val session: FakeChatSession,
    )

    companion object {
        val RADIO_A: Uuid = ChatFixtures.RADIO
        val RADIO_B: Uuid = Uuid.normalized("C0000000-0000-4000-8000-00000000000B")
        const val CHANNEL_INDEX = 1
        const val TIMEOUT_MS = 5_000L
        private const val CHANNEL_SECRET_LENGTH = 16
        const val TEST_TEXT = "radio admission fixture"
        private val CHANNEL_A: Uuid = Uuid.normalized("D0000000-0000-4000-8000-00000000000A")
        private val CHANNEL_B: Uuid = Uuid.normalized("D0000000-0000-4000-8000-00000000000B")
    }
}
