package one.meshcore.shared.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuickReplySendTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val other = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val contactId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

    @Test
    fun directReplyAwaitsTheRadioSend() = runTest {
        val sent = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        val reply = async {
            sendQuickReplyOnRadio(
                target = ConversationTarget.Direct(contactId),
                fetchContact = { id ->
                    assertEquals(contactId, id)
                    contact(radio)
                },
                isConnectionReady = { it == radio },
                sendDirect = {
                    entered.complete(Unit)
                    accepted.await()
                    sent += it.name
                },
                sendChannel = { _, _ -> error("channel") },
            )
        }
        entered.await()
        assertFalse(reply.isCompleted)
        assertEquals(emptyList(), sent)
        accepted.complete(Unit)
        assertTrue(reply.await())
        assertEquals(listOf("Alice"), sent)
    }

    @Test
    fun missingContactDoesNotSend() = runTest {
        val ok = sendQuickReplyOnRadio(
            target = ConversationTarget.Direct(contactId),
            fetchContact = { null },
            isConnectionReady = { it == radio },
            sendDirect = { error("direct") },
            sendChannel = { _, _ -> error("channel") },
        )
        assertFalse(ok)
    }

    @Test
    fun channelReplyAwaitsTheRadioSend() = runTest {
        val sent = mutableListOf<Pair<Int, Uuid>>()
        val entered = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        val reply = async {
            sendQuickReplyOnRadio(
                target = ConversationTarget.Channel(radio, 3),
                fetchContact = { error("contact lookup") },
                isConnectionReady = { it == radio },
                sendDirect = { error("direct") },
                sendChannel = { index, radioID ->
                    entered.complete(Unit)
                    accepted.await()
                    sent += index to radioID
                },
            )
        }
        entered.await()
        assertFalse(reply.isCompleted)
        assertEquals(emptyList(), sent)
        accepted.complete(Unit)
        assertTrue(reply.await())
        assertEquals(listOf(3 to radio), sent)
    }

    @Test
    fun channelReplyRejectsAnotherRadio() = runTest {
        val sent = mutableListOf<Pair<Int, Uuid>>()
        val ok = sendQuickReplyOnRadio(
            target = ConversationTarget.Channel(other, 3),
            fetchContact = { error("contact lookup") },
            isConnectionReady = { it == radio },
            sendDirect = { error("direct") },
            sendChannel = { index, radioID -> sent += index to radioID },
        )
        assertFalse(ok)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun directReplyRejectsAContactFromAnotherRadio() = runTest {
        val sent = mutableListOf<ContactDto>()
        val ok = sendQuickReplyOnRadio(
            target = ConversationTarget.Direct(contactId),
            fetchContact = { contact(other) },
            isConnectionReady = { it == radio },
            sendDirect = { sent += it },
            sendChannel = { _, _ -> error("channel") },
        )
        assertFalse(ok)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun directReplyDoesNotSendWhenDisconnected() = runTest {
        val sent = mutableListOf<ContactDto>()
        val ok = sendQuickReplyOnRadio(
            target = ConversationTarget.Direct(contactId),
            fetchContact = { contact(radio) },
            isConnectionReady = { false },
            sendDirect = { sent += it },
            sendChannel = { _, _ -> error("channel") },
        )
        assertFalse(ok)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun channelReplyDoesNotSendWhenDisconnected() = runTest {
        val ok = sendQuickReplyOnRadio(
            target = ConversationTarget.Channel(radio, 3),
            fetchContact = { error("contact lookup") },
            isConnectionReady = { false },
            sendDirect = { error("direct") },
            sendChannel = { _, _ -> error("channel") },
        )
        assertFalse(ok)
    }

    @Test
    fun roomReplyDoesNotResolveOrSend() = runTest {
        assertFalse(
            sendQuickReplyOnRadio(
                target = ConversationTarget.Room(contactId),
                fetchContact = { error("contact lookup") },
                isConnectionReady = { error("readiness") },
                sendDirect = { error("direct") },
                sendChannel = { _, _ -> error("channel") },
            ),
        )
    }

    @Test
    fun contactLookupCancellationPropagatesWithoutSending() = runTest {
        val cancellation = CancellationException("lookup cancelled")
        try {
            sendQuickReplyOnRadio(
                target = ConversationTarget.Direct(contactId),
                fetchContact = { throw cancellation },
                isConnectionReady = { error("readiness") },
                sendDirect = { error("direct") },
                sendChannel = { _, _ -> error("channel") },
            )
            error("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    private fun contact(radioID: Uuid) = ContactDto(
        id = contactId,
        radioID = radioID,
        publicKey = ByteArray(32),
        name = "Alice",
        typeRawValue = 1,
        flags = 0,
        outPathLength = -1,
        outPath = byteArrayOf(),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 2,
    )
}
