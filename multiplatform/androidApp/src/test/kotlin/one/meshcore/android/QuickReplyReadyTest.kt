package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ConversationTarget
import one.meshcore.shared.notification.sendQuickReplyOnRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickReplyReadyTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val other = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val contactId = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")

    @Test
    fun onlyMatchingReadyWithTheCurrentOwnerCanSend() {
        val graph = Graph(radio)
        assertTrue(graph.ready(radio))
        assertFalse(graph.ready(other))
        for (state in listOf(
            ConnectionState.Syncing("dev"),
            ConnectionState.Connected("dev"),
            ConnectionState.Connecting("dev"),
            ConnectionState.Disconnected,
            ConnectionState.Reconnecting("dev", 1),
            ConnectionState.BondLost("dev"),
        )) {
            graph.state = state
            assertFalse(graph.ready(radio))
        }
        graph.state = ConnectionState.Ready("dev")
        graph.currentOwner = null
        assertFalse(graph.ready(radio))
        graph.currentOwner = graph.owner
        graph.radioId = null
        assertFalse(graph.ready(radio))
    }

    @Test
    fun changingTheTransportHandleDoesNotChangeTheRadioPartition() {
        val graph = Graph(radio)
        graph.state = ConnectionState.Ready("re-paired-handle")
        assertTrue(graph.ready(radio))
        assertFalse(graph.ready(other))
    }

    @Test
    fun graphChangesDuringContactLookupRejectTheRetainedReply() = runTest {
        val changes: List<(Graph) -> Unit> = listOf(
            { it.currentOwner = Any() },
            { it.currentOwner = null },
            { it.state = ConnectionState.Disconnected },
            { it.state = ConnectionState.Syncing("dev") },
            { it.radioId = other },
        )
        for (change in changes) {
            val graph = Graph(radio)
            val entered = CompletableDeferred<Unit>()
            val contact = CompletableDeferred<ContactDto>()
            val sent = mutableListOf<ContactDto>()
            val reply = async {
                sendQuickReplyOnRadio(
                    target = ConversationTarget.Direct(contactId),
                    fetchContact = { id ->
                        assertEquals(contactId, id)
                        entered.complete(Unit)
                        contact.await()
                    },
                    isConnectionReady = graph.ready,
                    sendDirect = { sent += it },
                    sendChannel = { _, _ -> error("channel") },
                )
            }
            entered.await()
            change(graph)
            contact.complete(contact(radio))
            assertFalse(reply.await())
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun reconciliationSendsOnTheLivePartitionWithoutRewiring() = runTest {
        val graph = Graph(radio)
        val sent = mutableListOf<Uuid>()
        assertTrue(
            sendQuickReplyOnRadio(
                target = ConversationTarget.Direct(contactId),
                fetchContact = {
                    graph.radioId = other
                    contact(other)
                },
                isConnectionReady = graph.ready,
                sendDirect = { sent += it.radioID },
                sendChannel = { _, _ -> error("channel") },
            ),
        )
        assertTrue(
            sendQuickReplyOnRadio(
                target = ConversationTarget.Channel(other, 3),
                fetchContact = { error("contact lookup") },
                isConnectionReady = graph.ready,
                sendDirect = { error("direct") },
                sendChannel = { index, radioID ->
                    assertEquals(3, index)
                    sent += radioID
                },
            ),
        )
        assertEquals(listOf(other, other), sent)
        assertFalse(graph.ready(radio))
    }

    @Test
    fun replacingTheOwnerOnTheSameRadioRejectsARetainedChannelCallback() = runTest {
        val graph = Graph(radio)
        assertTrue(graph.ready(radio))
        graph.currentOwner = Any()
        assertFalse(
            sendQuickReplyOnRadio(
                target = ConversationTarget.Channel(radio, 3),
                fetchContact = { error("contact lookup") },
                isConnectionReady = graph.ready,
                sendDirect = { error("direct") },
                sendChannel = { _, _ -> error("channel") },
            ),
        )
    }

    private class Graph(initialRadio: Uuid) {
        val owner = Any()
        var currentOwner: Any? = owner
        var state: ConnectionState = ConnectionState.Ready("dev")
        var radioId: Uuid? = initialRadio
        val ready = quickReplyReadiness(
            owner = owner,
            currentOwner = { currentOwner },
            state = { state },
            radioId = { radioId },
        )
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
