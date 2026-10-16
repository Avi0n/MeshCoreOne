package one.meshcore.shared.navigation

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SendMessageShortcutEnqueueTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val otherRadio = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
    private val contactId = Uuid.normalized("C0000000-0000-0000-0000-000000000003")
    private val channelId = Uuid.normalized("D0000000-0000-0000-0000-000000000004")

    @Test
    fun syncingWithServicesIsReadyToQueue() {
        assertTrue(isSendMessageRadioReady(ConnectionState.Syncing("dev"), hasServices = true))
        assertTrue(isSendMessageRadioReady(ConnectionState.Ready("dev"), hasServices = true))
        assertTrue(!isSendMessageRadioReady(ConnectionState.Connected("dev"), hasServices = true))
        assertTrue(!isSendMessageRadioReady(ConnectionState.Syncing("dev"), hasServices = false))
    }

    @Test
    fun confirmDialogMapsEveryNonQueuedOutcome() {
        assertEquals(
            SendMessageShortcutDialog.None,
            sendMessageShortcutDialog(SendMessageShortcutOutcome.Queued),
        )
        assertEquals(
            SendMessageShortcutDialog.TooLong,
            sendMessageShortcutDialog(SendMessageShortcutOutcome.MessageTooLong),
        )
        assertEquals(
            SendMessageShortcutDialog.InvalidRecipient,
            sendMessageShortcutDialog(SendMessageShortcutOutcome.InvalidRecipient),
        )
        assertEquals(
            SendMessageShortcutDialog.NotConnected,
            sendMessageShortcutDialog(SendMessageShortcutOutcome.MustForeground),
        )
    }

    @Test
    fun disconnectedRadioForegrounds() = runTest {
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = dmRequest(),
                radioReady = false,
                currentRadioId = radio,
                fetchContact = { contact() },
                fetchChannel = { null },
                enqueueDm = {},
                enqueueChannel = {},
            ),
        )
        assertIs<SendMessageShortcutOutcome.MustForeground>(outcome)
    }

    @Test
    fun missingContactIsInvalid() = runTest {
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = dmRequest(),
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { null },
                fetchChannel = { null },
                enqueueDm = {},
                enqueueChannel = {},
            ),
        )
        assertIs<SendMessageShortcutOutcome.InvalidRecipient>(outcome)
    }

    @Test
    fun contactOnAnotherRadioForegrounds() = runTest {
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = dmRequest(),
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { contact().copy(radioID = otherRadio) },
                fetchChannel = { null },
                enqueueDm = {},
                enqueueChannel = {},
            ),
        )
        assertIs<SendMessageShortcutOutcome.MustForeground>(outcome)
    }

    @Test
    fun nonChatContactCannotBeEnqueued() = runTest {
        for (type in listOf(ContactType.REPEATER, ContactType.ROOM)) {
            var enqueued = false
            val outcome = enqueueSendMessageShortcut(
                ShortcutEnqueue(
                    request = dmRequest(),
                    radioReady = true,
                    currentRadioId = radio,
                    fetchContact = { contact().copy(typeRawValue = type.raw) },
                    fetchChannel = { null },
                    enqueueDm = { enqueued = true },
                    enqueueChannel = {},
                ),
            )
            assertEquals(SendMessageShortcutOutcome.InvalidRecipient, outcome)
            assertTrue(!enqueued)
        }
    }

    @Test
    fun readyContactEnqueuesTheDm() = runTest {
        var enqueued = false
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = dmRequest(),
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { contact() },
                fetchChannel = { null },
                enqueueDm = { enqueued = true },
                enqueueChannel = {},
            ),
        )
        assertEquals(SendMessageShortcutOutcome.Queued, outcome)
        assertTrue(enqueued)
    }

    @Test
    fun readyChannelEnqueuesTheBroadcast() = runTest {
        var enqueued = false
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = MeshCoreDeepLink.SendMessage(
                    kind = MeshCoreDeepLink.SendMessage.Kind.CHANNEL,
                    targetId = channelId.value,
                    text = "hi",
                ),
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { null },
                fetchChannel = { channel() },
                enqueueDm = {},
                enqueueChannel = { enqueued = true },
            ),
        )
        assertEquals(SendMessageShortcutOutcome.Queued, outcome)
        assertTrue(enqueued)
    }

    @Test
    fun channelTextOverTheNodeNameBudgetIsRejected() = runTest {
        var enqueued = false
        val nodeName = "RepeaterOne"
        val budget = channelShortcutBudgetBytes(nodeName)
        val outcome = enqueueSendMessageShortcut(
            ShortcutEnqueue(
                request = MeshCoreDeepLink.SendMessage(
                    kind = MeshCoreDeepLink.SendMessage.Kind.CHANNEL,
                    targetId = channelId.value,
                    text = "x".repeat(budget + 1),
                ),
                radioReady = true,
                currentRadioId = radio,
                fetchContact = { null },
                fetchChannel = { channel() },
                enqueueDm = {},
                enqueueChannel = { enqueued = true },
                localNodeName = nodeName,
            ),
        )
        assertEquals(SendMessageShortcutOutcome.MessageTooLong, outcome)
        assertTrue(!enqueued)
    }

    private fun dmRequest() = MeshCoreDeepLink.SendMessage(
        kind = MeshCoreDeepLink.SendMessage.Kind.CONTACT,
        targetId = contactId.value,
        text = "hello",
    )

    private fun contact() = ContactDto(
        id = contactId,
        radioID = radio,
        publicKey = ByteArray(32),
        name = "Alice",
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private fun channel() = ChannelDto(
        id = channelId,
        radioID = radio,
        index = 1,
        name = "Ops",
        secret = ByteArray(16),
        isEnabled = true,
        unreadCount = 0,
    )
}
