// AndroidOnly: WP-216 Real Room, mounted sync and App reaction-send classification at the GB11 boundary.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.isHiddenOutgoingReaction
import com.meshcoreone.android.core.services.rendering.HiddenOutgoingReactionPredicate
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionGraphemeClassificationTest : RoomProcessTest() {
    private val doubledJoiner = "\uD83D\uDC4D\u200D\u200D\uD83D\uDC4D"
    private val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
    private val peerKey = key(3)
    private val stamp = 1_704_067_200u
    private val targetName = "Node\uD83D\uDD25"

    @Test fun incomingChannelDoubledJoinerPersistsAnOrdinaryMessageInsteadOfAReaction() = runTest {
        withMounted(this) { assertIncoming(it, direct = false, doubledJoiner, accepted = false) }
    }

    @Test fun incomingDirectDoubledJoinerPersistsAnOrdinaryMessageInsteadOfAReaction() = runTest {
        withMounted(this) { assertIncoming(it, direct = true, doubledJoiner, accepted = false) }
    }

    @Test fun incomingFamilyReactionPreservesTheEmojiInItsTargetSenderName() = runTest {
        withMounted(this) { assertIncoming(it, direct = false, family, accepted = true) }
    }

    @Test fun incomingDirectSkinToneStillUpdatesItsStoredTargetWithoutAnOrdinaryMessage() = runTest {
        withMounted(this) { assertIncoming(it, direct = true, "\uD83D\uDC4D\uD83C\uDFFD", accepted = true) }
    }

    @Test fun appChannelSendPreservesDoubledJoinerWireAndKeepsItsOrdinaryMessageVisible() = runTest {
        withMounted(this) { assertOutgoing(it, direct = false, doubledJoiner, accepted = false) }
    }

    @Test fun appDirectSendPreservesDoubledJoinerWireAndKeepsItsOrdinaryMessageVisible() = runTest {
        withMounted(this) { assertOutgoing(it, direct = true, doubledJoiner, accepted = false) }
    }

    @Test fun appChannelFamilySendStillHasReactionClassificationAndVisibility() = runTest {
        withMounted(this) { assertOutgoing(it, direct = false, family, accepted = true) }
    }

    @Test fun appDirectFlagSendStillHasReactionClassificationAndVisibility() = runTest {
        withMounted(this) { assertOutgoing(it, direct = true, "\uD83C\uDDFA\uD83C\uDDF8", accepted = true) }
    }

    @Test fun generatedUnicodePropertiesReuseTheExistingApplicationAssetNotice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notice = context.assets.open("licenses/Unicode-License.txt").use { it.readBytes() }
        assertTrue(notice.toString(Charsets.UTF_8).startsWith("UNICODE LICENSE V3"))
        assertEquals(
            "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96",
            MessageDigest.getInstance("SHA-256").digest(notice)
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
        )
    }

    private data class Mounted(
        val harness: ContainerHarness,
        val live: RadioSessionContainer,
        val contact: ContactDTO,
        val channel: ChannelDTO,
    )

    private suspend fun withMounted(test: TestScope, body: suspend (Mounted) -> Unit) {
        val clock = object : SyncClock {
            override fun now() = EPOCH.plusSeconds(2)
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val harness = ContainerHarness(test, store, syncClock = clock)
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1f -> if (command[1] == 0.toUByte()) {
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                    } else null
                    0x02 -> sentAndDelivered(command, key(7))
                    0x03 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        try {
            assertEquals(EPOCH.epochSecond.toUInt(), stamp)
            harness.manager.connect(harness.target())
            harness.eventually("grapheme classification session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            val channel = store.fetchChannels(live.token.radioId).single()
            assertEquals(secret, channel.secret)
            val peer = ContactDTO(radioId = live.token.radioId, publicKey = peerKey, name = "Bob", lastHeardTimestamp = null)
            store.saveContact(peer)
            body(Mounted(harness, live, peer, channel))
            assertTrue(live.reportedGaps.isEmpty())
            assertTrue(harness.messagingDiagnostics.isEmpty(), harness.messagingDiagnostics.toString())
        } finally {
            harness.container.close()
        }
    }

    private suspend fun messages(fixture: Mounted, direct: Boolean): List<MessageDTO> =
        if (direct) store.fetchMessages(EntityKey(fixture.contact.radioId, fixture.contact.id))
        else store.fetchMessages(fixture.channel.radioId, fixture.channel.index)

    private suspend fun target(fixture: Mounted, direct: Boolean): MessageDTO {
        poll(fixture, direct, if (direct) "Hello" else "$targetName: Hello", stamp)
        val target = messages(fixture, direct).single()
        assertEquals("Hello", target.text)
        assertEquals(stamp, target.reactionTimestamp)
        assertFalse(target.timestampCorrected)
        if (!direct) assertEquals(targetName, target.senderNodeName)
        return target
    }

    private fun wire(emoji: String, direct: Boolean): String =
        if (direct) "$emoji\nb45pc4ek" else "@[$targetName]$emoji\nb45pc4ek"

    private suspend fun assertIncoming(fixture: Mounted, direct: Boolean, emoji: String, accepted: Boolean) {
        val target = target(fixture, direct)
        val wire = wire(emoji, direct)
        poll(fixture, direct, if (direct) wire else "Bob: $wire", stamp + 1u)
        val saved = messages(fixture, direct)
        val targetKey = EntityKey(target.radioId, target.id)
        val reactions = store.fetchReactions(targetKey)
        if (accepted) {
            assertEquals(listOf(target.id), saved.map { it.id })
            val reaction = reactions.single()
            assertEquals(emoji, reaction.emoji)
            assertEquals("b45pc4ek", reaction.messageHash)
            assertEquals(wire, reaction.rawText)
            assertEquals("Bob", reaction.senderName)
            assertEquals(target.id, reaction.messageID)
            assertEquals(target.radioId, reaction.radioId)
            assertEquals(if (direct) fixture.contact.id else null, reaction.contactID)
            assertEquals(if (direct) null else fixture.channel.index, reaction.channelIndex)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(targetKey)).reactionSummary)
        } else {
            assertEquals(2, saved.size)
            val ordinary = saved.single { it.id != target.id }
            assertEquals(wire, ordinary.text)
            assertEquals(MessageDirection.INCOMING, ordinary.direction)
            assertEquals(MessageStatus.DELIVERED, ordinary.status)
            assertEquals(if (direct) fixture.contact.id else null, ordinary.contactID)
            assertEquals(if (direct) null else fixture.channel.index, ordinary.channelIndex)
            if (!direct) assertEquals("Bob", ordinary.senderNodeName)
            assertTrue(reactions.isEmpty())
            assertTrue(store.fetchReactions(EntityKey(ordinary.radioId, ordinary.id)).isEmpty())
            assertNull(assertNotNull(store.fetchMessage(targetKey)).reactionSummary)
        }
    }

    private suspend fun assertOutgoing(fixture: Mounted, direct: Boolean, emoji: String, accepted: Boolean) {
        val target = target(fixture, direct)
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        fixture.harness.container.chatsFeature.react(target, emoji, conversation)
        val expectedStatus = if (direct) MessageStatus.DELIVERED else MessageStatus.SENT
        fixture.harness.eventually("actual App reaction-send committed status") {
            runBlocking {
                messages(fixture, direct).singleOrNull { it.direction == MessageDirection.OUTGOING }?.status == expectedStatus
            }
        }
        val saved = messages(fixture, direct)
        assertEquals(2, saved.size)
        val outgoing = saved.single { it.direction == MessageDirection.OUTGOING }
        val expectedWire = wire(emoji, direct)
        assertEquals(expectedWire, outgoing.text)
        assertEquals(target.radioId, outgoing.radioId)
        assertEquals(if (direct) fixture.contact.id else null, outgoing.contactID)
        assertEquals(if (direct) null else fixture.channel.index, outgoing.channelIndex)
        val commandCode = if (direct) 0x02 else 0x03
        val frame = fixture.harness.links.radios.last().sent.single { it[0].toInt() == commandCode }
        assertEquals(expectedWire, frame.toByteArray().copyOfRange(if (direct) 13 else 7, frame.size).toString(Charsets.UTF_8))
        val classifier = fixture.harness.container.chatsFeature.listFor(target.radioId).reactions
        assertEquals(accepted, if (direct) classifier.isDirectReaction(outgoing.text) else classifier.isChannelReaction(outgoing.text))
        assertEquals(accepted, outgoing.isHiddenOutgoingReaction(isDM = direct))
        val rendering = HiddenOutgoingReactionPredicate.SourceWireFormat
        assertEquals(accepted, rendering.isHiddenOutgoingReaction(outgoing, isDM = direct))
        assertFalse(outgoing.copy(status = MessageStatus.FAILED).isHiddenOutgoingReaction(isDM = direct))
        assertFalse(rendering.isHiddenOutgoingReaction(outgoing.copy(status = MessageStatus.FAILED), isDM = direct))
        assertTrue(store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty())
    }

    private suspend fun poll(fixture: Mounted, direct: Boolean, text: String, timestamp: UInt) {
        val radio = fixture.harness.links.radios.last()
        val previous = radio.respond
        val frame = if (direct) {
            Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
        } else {
            Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
        }
        val frames = ConcurrentLinkedQueue(listOf(frame))
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else previous(command)
        }
        try {
            assertEquals(1L, fixture.live.messagePollingService.pollAllMessages())
        } finally {
            radio.respond = previous
        }
    }
}
