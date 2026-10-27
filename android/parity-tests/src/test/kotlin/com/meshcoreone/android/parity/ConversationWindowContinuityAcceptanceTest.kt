// PortedFrom: MC1Tests/Views/Chats/ChatReconnectPopulateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: WP-501 Supplemental real App/Room witnesses, not complete original-family replacements.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationWindowContinuityAcceptanceTest : RoomProcessTest() {
    @Test fun directRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows() = runTest {
        withTimeline(isChannel = false, storedCount = 112) {
            assertPagedWindowPreserved()
        }
    }

    @Test fun channelRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows() = runTest {
        withTimeline(isChannel = true, storedCount = 112) {
            assertPagedWindowPreserved()
        }
    }

    @Test fun directRefreshDoesNotAdmitTheFiftyFirstProbeRow() = runTest {
        withTimeline(isChannel = false, storedCount = 51) {
            assertProbeStaysOutsideWindow()
        }
    }

    @Test fun channelRefreshDoesNotAdmitTheFiftyFirstProbeRow() = runTest {
        withTimeline(isChannel = true, storedCount = 51) {
            assertProbeStaysOutsideWindow()
        }
    }

    @Test fun directRefreshKeepsSentReactionRowsStoredButOutsideTheVisibleWindow() = runTest {
        withTimeline(isChannel = false, storedCount = 62) {
            viewModel.loadOlder()
            assertNull(viewModel.state.value.passiveError)
            val expectedIDs = stored.map { it.id }
            assertEquals(expectedIDs, visibleIDs())
            assertFalse(viewModel.state.value.hasMoreMessages)

            val hidden = (0..1).map { index ->
                val timestamp = 1062u + index.toUInt()
                stored.last().copy(
                    id = UUID.randomUUID(),
                    text = "\uD83D\uDC4D\nABCDEFGH",
                    timestamp = timestamp,
                    createdAt = Instant.ofEpochSecond(timestamp.toLong()),
                    sortDate = Instant.ofEpochSecond(timestamp.toLong()),
                    direction = MessageDirection.OUTGOING,
                    status = MessageStatus.SENT,
                    isRead = true,
                ).also { store.saveMessage(it) }
            }
            for (message in hidden) {
                assertEquals(message, store.fetchMessage(EntityKey(message.radioId, message.id)))
            }
            assertEquals(expectedIDs, visibleIDs(), "Saving alone must not mutate the screen's loaded window")

            refreshSuccessfully()

            assertEquals(expectedIDs, visibleIDs(), "Refresh must retain all 62 visible rows and hide both sent reactions")
            assertEquals(expectedIDs.first(), viewModel.state.value.messages.first().id)
            assertFalse(viewModel.state.value.hasMoreMessages)
            for (message in hidden) {
                assertNotNull(store.fetchMessage(EntityKey(message.radioId, message.id)), "Hiding must not delete the wire row")
            }
        }
    }

    @Test fun directRefreshCatchesUpNewRowsWithoutDiscardingFullyLoadedHistory() = runTest {
        withTimeline(isChannel = false, storedCount = 62) {
            viewModel.loadOlder()
            assertNull(viewModel.state.value.passiveError)
            val expectedIDs = stored.map { it.id }
            assertEquals(expectedIDs, visibleIDs())
            val incoming = (0..2).map { index ->
                val timestamp = 1062u + index.toUInt()
                stored.last().copy(
                    id = UUID.randomUUID(),
                    text = "message $timestamp",
                    timestamp = timestamp,
                    createdAt = Instant.ofEpochSecond(timestamp.toLong()),
                    sortDate = Instant.ofEpochSecond(timestamp.toLong()),
                ).also { store.saveMessage(it) }
            }
            assertEquals(expectedIDs, visibleIDs())

            refreshSuccessfully()

            assertEquals(expectedIDs + incoming.map { it.id }, visibleIDs())
            assertEquals(65, viewModel.state.value.totalFetchedCount)
            assertFalse(viewModel.state.value.hasMoreMessages)
            assertEquals(expectedIDs.first(), viewModel.state.value.messages.first().id)
        }
    }

    @Test fun directExactPageRefreshDoesNotInventFurtherHistory() = runTest {
        withTimeline(isChannel = false, storedCount = 50) { assertExactPagePreserved() }
    }

    @Test fun channelExactPageRefreshDoesNotInventFurtherHistory() = runTest {
        withTimeline(isChannel = true, storedCount = 50) { assertExactPagePreserved() }
    }

    private suspend fun Rig.assertPagedWindowPreserved() {
        viewModel.loadOlder()
        assertNull(viewModel.state.value.passiveError)
        val expectedIDs = stored.drop(12).map { it.id }
        assertEquals(100, expectedIDs.size)
        assertEquals(expectedIDs, visibleIDs())
        assertEquals(100, viewModel.state.value.totalFetchedCount)
        assertTrue(viewModel.state.value.hasMoreMessages)
        assertFalse(viewModel.state.value.isLoadingOlder)

        refreshSuccessfully()

        assertEquals(expectedIDs, visibleIDs(), "Refresh is not a request to page in the remaining twelve older rows")
        assertEquals(100, viewModel.state.value.totalFetchedCount)
        assertTrue(viewModel.state.value.hasMoreMessages)
        assertFalse(viewModel.state.value.isLoadingOlder)
        viewModel.loadOlder()
        assertNull(viewModel.state.value.passiveError)
        assertEquals(stored.map { it.id }, visibleIDs(), "The next explicit page must abut the preserved window")
        assertEquals(112, viewModel.state.value.totalFetchedCount)
        assertFalse(viewModel.state.value.hasMoreMessages)
    }

    private suspend fun Rig.assertProbeStaysOutsideWindow() {
        val expectedIDs = stored.drop(1).map { it.id }
        assertEquals(50, expectedIDs.size)
        assertEquals(expectedIDs, visibleIDs())
        assertEquals(50, viewModel.state.value.totalFetchedCount)
        assertTrue(viewModel.state.value.hasMoreMessages)

        refreshSuccessfully()

        assertEquals(expectedIDs, visibleIDs(), "The 51st stored row is a probe, not another loaded message")
        assertEquals(50, viewModel.state.value.totalFetchedCount)
        assertTrue(viewModel.state.value.hasMoreMessages)
        viewModel.loadOlder()
        assertNull(viewModel.state.value.passiveError)
        assertEquals(stored.map { it.id }, visibleIDs())
        assertEquals(51, viewModel.state.value.totalFetchedCount)
        assertFalse(viewModel.state.value.hasMoreMessages)
    }

    private suspend fun Rig.assertExactPagePreserved() {
        val expectedIDs = stored.map { it.id }
        assertEquals(expectedIDs, visibleIDs())
        assertEquals(50, viewModel.state.value.totalFetchedCount)
        assertFalse(viewModel.state.value.hasMoreMessages)

        refreshSuccessfully()

        assertEquals(expectedIDs, visibleIDs())
        assertEquals(50, viewModel.state.value.totalFetchedCount)
        assertFalse(viewModel.state.value.hasMoreMessages)
    }

    private data class Rig(val viewModel: ChatViewModel, val stored: List<MessageDTO>) {
        fun visibleIDs() = viewModel.state.value.messages.map { it.id }

        suspend fun refreshSuccessfully() {
            viewModel.refresh()
            assertNull(viewModel.state.value.passiveError, "A failed fetch cannot stand in for a preserved window")
        }
    }

    private suspend fun TestScope.withTimeline(
        isChannel: Boolean,
        storedCount: Int,
        assertions: suspend Rig.() -> Unit,
    ) {
        val radio = RadioId(UUID.randomUUID())
        val conversation = if (isChannel) {
            val channel = ChannelDTO(
                radioId = radio, index = 3u, name = "General", secret = Bytes(ByteArray(0)),
                lastMessageDate = Instant.EPOCH,
            )
            store.saveChannel(channel)
            TimelineConversation.Channel(channel)
        } else {
            val contact = ContactDTO(
                radioId = radio, name = "TestContact", publicKey = Bytes(ByteArray(32) { 0x42 }),
                outPathLength = 2u, outPath = Bytes.of(0x01, 0x02), lastHeardTimestamp = null,
                lastMessageDate = Instant.EPOCH,
            )
            store.saveContact(contact)
            TimelineConversation.Direct(contact)
        }
        val stored = (0 until storedCount).map { index ->
            val timestamp = (1000 + index).toUInt()
            MessageDTO(
                radioId = radio,
                contactID = (conversation as? TimelineConversation.Direct)?.contact?.id,
                channelIndex = (conversation as? TimelineConversation.Channel)?.channel?.index,
                text = if (isChannel) "channel $timestamp" else "message $timestamp",
                timestamp = timestamp,
                createdAt = Instant.ofEpochSecond(timestamp.toLong()),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
                senderNodeName = if (isChannel) {
                    if (index == 0) "OldestSender" else "Sender${index % 3}"
                } else null,
            ).also { store.saveMessage(it) }
        }
        val harness = ContainerHarness(this, store)
        try {
            val viewModel = ChatViewModel(conversation, harness.container.chatsFeature.timeline(conversation), backgroundScope)
            viewModel.open()
            assertTrue(viewModel.state.value.hasLoadedOnce)
            assertFalse(viewModel.state.value.isLoading)
            assertNull(viewModel.state.value.loadError)
            Rig(viewModel, stored).assertions()
            assertTrue(harness.lifecycle.created.isEmpty(), "Offline history must not create a radio session")
            assertTrue(harness.links.links.isEmpty(), "Offline history must not create a transport")
        } finally {
            harness.container.close()
        }
    }
}
