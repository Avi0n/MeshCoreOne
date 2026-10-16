package one.meshcore.shared.connection

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.chat.ChannelMessageNotice
import one.meshcore.shared.chat.ChatErrorMessages
import one.meshcore.shared.chat.ConversationListViewModel
import one.meshcore.shared.chat.DirectMessageEnvelope
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.MessageServiceConfig
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.awaitDrainCompletion
import one.meshcore.shared.chat.createPendingMessage
import one.meshcore.shared.chat.enqueueDM
import one.meshcore.shared.chat.reload
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE W2 EXIT GATE. Drives the full committed chat pipeline — `ConnectionManager` -> `ServiceContainer`'s
 * message service, durable send queue, ingest poller, and coordinator registry -> Room -> the real Kotlin
 * `MeshCoreSession` -> the JNI shim -> `libMeshCoreBridgeKit.so` -> the Swift MeshCore core -> a fake
 * scripted transport — on the emulator, proving DM send and receive end to end against the mock:
 *
 *  1. connect + handshake + contact sync persist a `Ready` connection with the contact in Room;
 *  2. a DM send goes `.pending` (optimistic) then `.sent` (the retry budget settles it, no delivery ack),
 *     and the row is visible through the thread's own store fetch;
 *  3. an inbound contact-message frame arrives on the live event stream, ingests through the pipeline,
 *     persists as incoming, appears in the thread and the conversation list, fires the notifier, and bumps
 *     the contact's unread count;
 *  4. the conversation list shows the newest-message preview and the unread badge;
 *  5. disconnect tears the per-connection graph down.
 *
 * The canned frames are built to the exact wire layouts the Swift `MeshCore` parsers decode; the send uses
 * a one-attempt [MessageServiceConfig] and a short suggested-timeout frame so the no-delivery-ack settle to
 * `.sent` is deterministic and fast.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Numeric timings and payloads are deliberate integration fixtures.
class ConnectionExitGateInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "w2-exit-gate-${System.nanoTime()}.db"
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db = meshDatabase(context, Dispatchers.IO, dbName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    @Suppress("LongMethod") // The exit gate is one linear end-to-end scenario; splitting it would obscure the flow.
    fun dmSendAndReceiveEndToEndThroughTheRealGraph() = runBlocking {
        val link = ScriptedLink()
        val notifier = RecordingNotifier()

        // The firmware speaks request/response: this responder feeds the canned reply for each command the
        // session emits so the ceremony (start -> queryDevice -> getContacts -> channel sync -> backlog
        // drain) and the send path (send + getContactByKey routing-change probe) resolve without a real
        // radio. The device reports maxChannels=8, so the post-connect ceremony's pipelined channel sync
        // primes a getChannel(index) request per slot; each answers as unconfigured (empty name, zero
        // secret) so the fast path completes instead of falling to the slow serial-retry reconciliation.
        // The backlog drain's getMessage loop stops on the first reply, so one noMoreMessages answers it.
        val responder = scope.launch {
            while (true) {
                val command = link.nextOutbound()
                when (command[0].toInt() and 0xFF) {
                    CMD_APP_START -> link.deliver(selfInfoFrame())
                    CMD_DEVICE_QUERY -> link.deliver(deviceInfoFrame())
                    CMD_GET_CONTACTS -> {
                        link.deliver(contactStartFrame(count = 1))
                        link.deliver(contactFrame(CONTACT_PUBLIC_KEY, "Alice"))
                        link.deliver(contactEndFrame())
                    }
                    CMD_SEND_TXT_MSG -> link.deliver(messageSentFrame())
                    CMD_GET_CONTACT_BY_KEY -> link.deliver(contactFrame(CONTACT_PUBLIC_KEY, "Alice"))
                    CMD_GET_CHANNEL -> link.deliver(unconfiguredChannelInfoFrame(command[1].toInt() and 0xFF))
                    CMD_GET_MESSAGE -> link.deliver(noMoreMessagesFrame())
                    // iOS issues both during connect too: getAutoAddConfig overlaps the BLE roundtrip
                    // and getTime feeds the clock-drift check. A real radio answers them, so the
                    // responder must as well or the ceremony never completes.
                    CMD_GET_AUTO_ADD_CONFIG -> link.deliver(autoAddConfigFrame())
                    CMD_GET_TIME -> link.deliver(currentTimeFrame())
                }
            }
        }

        // A monotonically increasing clock so each persisted message gets a distinct, ordered sortDate and
        // the newest (the inbound reply) wins the conversation-list preview; delays use real time, not this.
        val clock = java.util.concurrent.atomic.AtomicLong(NOW_EPOCH_SECONDS.toLong())

        val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = { link },
            sessionFactory = { MeshCoreSession(it) },
            nowEpochSeconds = { clock.getAndIncrement().toDouble() },
            ports = ConnectionManagerPorts(
                newRadioId = { radioId },
                notifier = notifier,
                // One attempt, so the missing delivery ack settles the DM to `.sent` after a single short wait.
                messageServiceConfig = MessageServiceConfig(maxAttempts = 1),
            ),
        )

        // 1) Connect: handshake, capabilities, contact sync -> Ready, contact persisted to Room.
        withTimeout(TIMEOUT_MS) { manager.connect(DEVICE) }
        assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
        assertEquals(radioId, manager.radioId)
        val services = requireNotNull(manager.services) { "services must exist while Ready" }

        val contact = requireNotNull(services.contactService.getContact(radioId, CONTACT_PUBLIC_KEY)) {
            "Alice must have synced into the contact store"
        }
        assertEquals("Alice", contact.name)

        // 2) DM send: optimistic `.pending`, then the queue drains it to `.sent`, visible in the thread fetch.
        val pending = services.messageService.createPendingMessage(text = "hello", to = contact)
        assertEquals(MessageStatus.PENDING, requireNotNull(services.chatStore.fetchMessage(pending.id)).status)

        services.sendQueue.enqueueDM(DirectMessageEnvelope(messageID = pending.id, contactID = contact.id))
        withTimeout(TIMEOUT_MS) { services.sendQueue.awaitDrainCompletion() }
        val sent = awaitMessageStatus(services, pending.id, MessageStatus.SENT)
        assertEquals(MessageDirection.OUTGOING, sent.direction)

        val threadAfterSend = services.chatStore.fetchDirectMessages(contact.id, THREAD_PAGE, 0)
        assertTrue("sent DM must appear in the thread", threadAfterSend.any { it.text == "hello" })

        // 3) Inbound DM: arrives on the live event stream, ingests, persists incoming, bumps unread, notifies.
        link.deliver(contactMessageFrame(CONTACT_PUBLIC_KEY, "hi there"))
        val received = awaitThreadMessage(services, contact.id) { it.text == "hi there" }
        assertEquals(MessageDirection.INCOMING, received.direction)
        assertTrue("notifier must fire for the inbound DM", notifier.awaitDirectMessage("hi there"))
        assertEquals(1, requireNotNull(db.contactDao().getById(contact.id)).unreadCount)

        // 4) Conversation list: the contact shows the newest-message preview and its unread badge.
        val listViewModel = ConversationListViewModel(
            ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { services.conversationListStore },
                radioId = { radioId },
                isReady = { true },
            ),
        )
        withTimeout(TIMEOUT_MS) { listViewModel.reload() }
        val listState = listViewModel.state.value
        assertEquals("hi there", listState.previews[contact.id])
        val row = listState.snapshot.pinned.plus(listState.snapshot.others).firstOrNull { it.id == contact.id }
        assertNotNull("Alice must be a conversation-list row", row)
        assertEquals(1, requireNotNull(row).unreadCount)

        // 5) Disconnect tears the per-connection graph down.
        responder.cancel()
        withTimeout(TIMEOUT_MS) { manager.disconnect() }
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)
    }

    /** Polls Room via the thread store until [id] reaches [status], so async persistence can settle. */
    private suspend fun awaitMessageStatus(services: ServiceContainer, id: Uuid, status: MessageStatus) =
        requireNotNull(
            withTimeoutOrNull(TIMEOUT_MS) {
                var match = services.chatStore.fetchMessage(id)
                while (match?.status != status) {
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                    match = services.chatStore.fetchMessage(id)
                }
                match
            },
        ) { "message $id did not reach $status within ${TIMEOUT_MS}ms" }

    /** Polls the thread page until a message matching [predicate] appears. */
    private suspend fun awaitThreadMessage(
        services: ServiceContainer,
        contactID: Uuid,
        predicate: (one.meshcore.shared.backup.MessageDto) -> Boolean,
    ) = requireNotNull(
        withTimeoutOrNull(TIMEOUT_MS) {
            var match: one.meshcore.shared.backup.MessageDto? = null
            while (match == null) {
                match = services.chatStore.fetchDirectMessages(contactID, THREAD_PAGE, 0).firstOrNull(predicate)
                if (match == null) kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
            match
        },
    ) { "expected a thread message within ${TIMEOUT_MS}ms" }

    /** Records the ingest notifier's direct-message posts so the test can assert the notification path fired. */
    private class RecordingNotifier : IngestNotifier {
        private val mutex = Mutex()
        private val directTexts = mutableListOf<String>()

        override suspend fun postDirectMessage(
            contact: ResolvedContact,
            text: String,
            messageID: Uuid,
            hasSelfMention: Boolean,
        ) = mutex.withLock { directTexts += text }

        override suspend fun postChannelMessage(notice: ChannelMessageNotice) = Unit

        override suspend fun recordUnresolvedChannel(channelIndex: Int, radioID: Uuid, senderTimestamp: Long) = Unit

        override suspend fun updateBadge() = Unit

        override suspend fun notifyConversationsChanged() = Unit

        suspend fun awaitDirectMessage(text: String): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
            while (mutex.withLock { text !in directTexts }) kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            true
        } ?: false
    }

    /** A [MeshLink] whose outbound frames the test inspects and whose inbound frames the test injects. */
    private class ScriptedLink : MeshLink {
        private var listener: MeshLinkListener? = null
        private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

        override fun setListener(listener: MeshLinkListener?) {
            this.listener = listener
        }

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit

        override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
            outbound.trySend(bytes)
        }

        suspend fun nextOutbound(): ByteArray = outbound.receive()

        fun deliver(frame: ByteArray) {
            listener?.onReceived(frame)
        }
    }

    /** Ends the backlog drain's `getMessage` loop; the test radio has no queued messages. */
    private fun noMoreMessagesFrame(): ByteArray = buildFrame(RES_NO_MORE_MESSAGES) {}

    /** Device clock, LE u32 seconds, matching the phone so the drift check stays quiet. */
    private fun currentTimeFrame(): ByteArray = buildFrame(RES_CURRENT_TIME) {
        val epoch = NOW_EPOCH_SECONDS.toLong().toInt()
        add(epoch and 0xFF)
        add((epoch shr 8) and 0xFF)
        add((epoch shr 16) and 0xFF)
        add((epoch shr 24) and 0xFF)
    }

    private companion object {
        const val DEVICE = "emulator-fake-radio"
        const val TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 25L
        const val NOW_EPOCH_SECONDS = 1_000.0
        const val SENDER_TIMESTAMP = 1_700_000_000L
        const val THREAD_PAGE = 50

        // Command opcodes the session emits (MeshCore CommandCode).
        const val CMD_APP_START = 0x01
        const val CMD_SEND_TXT_MSG = 0x02
        const val CMD_GET_CONTACTS = 0x04
        const val CMD_GET_MESSAGE = 0x0A
        const val CMD_GET_TIME = 0x05
        const val CMD_GET_AUTO_ADD_CONFIG = 0x3B
        const val CMD_DEVICE_QUERY = 0x16
        const val CMD_GET_CONTACT_BY_KEY = 0x1E
        const val CMD_GET_CHANNEL = 0x1F

        // Response opcodes (MeshCore ResponseCode).
        const val RES_SELF_INFO = 0x05
        const val RES_CONTACT_START = 0x02
        const val RES_CONTACT = 0x03
        const val RES_CONTACT_END = 0x04
        const val RES_DEVICE_INFO = 0x0D
        const val RES_CURRENT_TIME = 0x09
        const val RES_AUTO_ADD_CONFIG = 0x19
        const val RES_MESSAGE_SENT = 0x06
        const val RES_CONTACT_MESSAGE = 0x07
        const val RES_NO_MORE_MESSAGES = 0x0A
        const val RES_CHANNEL_INFO = 0x12

        const val PUBLIC_KEY_LENGTH = 32
        val CONTACT_PUBLIC_KEY = ByteArray(PUBLIC_KEY_LENGTH) { 0x09 }

        // `Parsers.ChannelInfo` layout: [index:1][name:32 null-padded][secret:16].
        const val CHANNEL_NAME_FIELD_LENGTH = 32
        const val CHANNEL_SECRET_LENGTH = 16

        // --- Frame builders. Layouts mirror the Swift MeshCore parsers byte-for-byte. ---

        fun selfInfoFrame(): ByteArray = buildFrame(RES_SELF_INFO) {
            add(1) // advertisement type
            add(22) // tx power
            add(22) // max tx power
            repeat(PUBLIC_KEY_LENGTH) { add(0x01) } // public key
            le32(0) // latitude
            le32(0) // longitude
            le32(0) // multiAcks/advPolicy/telemetryMode/manualAdd (all zero)
            le32(915_000) // radio frequency (kHz)
            le32(125_000) // radio bandwidth (Hz)
            add(7) // spreading factor
            add(5) // coding rate
            ascii("Test") // name
        }

        fun deviceInfoFrame(): ByteArray = buildFrame(RES_DEVICE_INFO) {
            add(10) // firmware version
            add(50) // max contacts / 2 -> 100
            add(8) // max channels
            le32(0) // BLE PIN
            fixedAscii("01 Jan 2026", 12) // firmware build
            fixedAscii("Heltec", 40) // model
            fixedAscii("v1.15.0", 20) // hardware version
            add(0) // client_repeat (v9+)
            add(0) // path_hash_mode (v10+)
        }

        fun contactStartFrame(count: Int): ByteArray = buildFrame(RES_CONTACT_START) { le32(count) }

        /** `Parsers.parseContactData` 147-byte layout; flood out-path (parser maps 0xFF to empty). */
        fun contactFrame(publicKey: ByteArray, name: String): ByteArray = buildFrame(RES_CONTACT) {
            bytes(publicKey) // public key (32)
            add(0x01) // contact type = chat
            add(0) // flags
            add(0xFF) // path length: flood
            repeat(64) { add(0) } // routing path field
            fixedAscii(name, 32) // advertised name (null-padded)
            le32(0) // last advertisement
            le32(0) // latitude
            le32(0) // longitude
            le32(0) // last modified
        }

        fun contactEndFrame(): ByteArray = buildFrame(RES_CONTACT_END) { le32(0) }

        /** `messageSent` -> `[route:1][expectedAck:4][suggestedTimeoutMs: UInt32 LE]`; short timeout for the test. */
        fun messageSentFrame(): ByteArray = buildFrame(RES_MESSAGE_SENT) {
            add(0) // route
            bytes(byteArrayOf(0x11, 0x22, 0x33, 0x44)) // expected ack (never delivered, so the DM settles to .sent)
            le32(200) // suggested timeout ms
        }

        /** `Parsers.ContactMessage` v1 layout -> `[pubkeyPrefix:6][pathLen:1][txtType:1][ts:4][text]`. */
        fun contactMessageFrame(publicKey: ByteArray, text: String): ByteArray = buildFrame(RES_CONTACT_MESSAGE) {
            bytes(publicKey.copyOf(6)) // sender public-key prefix
            add(0) // path length
            add(0) // text type = plain
            le32(SENDER_TIMESTAMP.toInt()) // sender timestamp
            ascii(text)
        }

        /** Answers `getChannel(index)` as unconfigured (empty name, zero secret) — an unset slot. */
        fun unconfiguredChannelInfoFrame(index: Int): ByteArray = buildFrame(RES_CHANNEL_INFO) {
            add(index)
            repeat(CHANNEL_NAME_FIELD_LENGTH) { add(0) } // empty name
            repeat(CHANNEL_SECRET_LENGTH) { add(0) } // zero secret
        }

        /** Auto-add off, zero max hops: one bitmask byte plus the hop byte the parser reads when present. */
        fun autoAddConfigFrame(): ByteArray = buildFrame(RES_AUTO_ADD_CONFIG) {
            add(0)
            add(0)
        }

        fun buildFrame(code: Int, body: FrameBuilder.() -> Unit): ByteArray {
            val builder = FrameBuilder()
            builder.add(code)
            builder.body()
            return builder.toByteArray()
        }

        private class FrameBuilder {
            private val out = ArrayList<Byte>()

            fun add(value: Int) {
                out.add(value.toByte())
            }

            fun bytes(value: ByteArray) {
                value.forEach { out.add(it) }
            }

            fun le32(value: Int) {
                add(value and 0xFF)
                add((value ushr 8) and 0xFF)
                add((value ushr 16) and 0xFF)
                add((value ushr 24) and 0xFF)
            }

            fun ascii(value: String) {
                value.encodeToByteArray().forEach { out.add(it) }
            }

            fun fixedAscii(value: String, length: Int) {
                val encoded = value.encodeToByteArray()
                for (i in 0 until length) out.add(if (i < encoded.size) encoded[i] else 0)
            }

            fun toByteArray(): ByteArray = out.toByteArray()
        }
    }
}
