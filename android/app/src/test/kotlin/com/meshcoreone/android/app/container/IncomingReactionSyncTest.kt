// AndroidOnly: WP-216 Real Room and mounted polling regressions for incoming reaction forwarding.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncClock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingReactionSyncTest : RoomProcessTest() {
    private val emoji = "\uD83D\uDC4D"
    private val peerKey = key(3)
    private val timestamp = 1_704_067_200u

    @Test fun pendingChannelReactionIsAppliedWhenItsTargetArrives() = runTest {
        assertMountedReaction(this, direct = false, "@[Alice]$emoji\nb45pc4ek", "b45pc4ek", pending = true)
    }

    @Test fun pendingDirectReactionIsAppliedWhenItsTargetArrives() = runTest {
        assertMountedReaction(this, direct = true, "$emoji\nb45pc4ek", "b45pc4ek", pending = true)
    }

    @Test fun meshCoreOpenV3UsesTheStoredChannelTargetWithoutSavingAnOrdinaryMessage() = runTest {
        assertMountedReaction(this, direct = false, "r:aa61:00", "aa61", pending = false)
    }

    @Test fun meshCoreOpenV1UsesTheStoredDirectTargetWithoutSavingAnOrdinaryMessage() = runTest {
        assertMountedReaction(
            this, direct = true, "r:1704067200000_210078619_828172268:$emoji",
            "1704067200_210078619_828172268", pending = false,
        )
    }

    private suspend fun assertMountedReaction(
        test: TestScope, direct: Boolean, wireText: String, expectedHash: String, pending: Boolean,
    ) {
        val syncClock = object : SyncClock {
            override fun now() = EPOCH.plusMillis(test.testScheduler.currentTime)
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }
        val harness = ContainerHarness(test, store, syncClock = syncClock)
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            assertEquals(EPOCH.epochSecond.toUInt(), timestamp)
            harness.manager.connect(harness.target())
            harness.eventually("mounted reaction session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            val channelSync = live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            val channels = store.fetchChannels(live.token.radioId)
            assertEquals(1, channels.size,
                "configured channel after sync (direct=$direct, pending=$pending, sync=$channelSync)")
            assertEquals(secret, channels.single().secret)
            val peer = ContactDTO(radioId = live.token.radioId, publicKey = peerKey, name = "Bob", lastHeardTimestamp = null)
            store.saveContact(peer)
            val contact = EntityKey(live.token.radioId, peer.id)
            suspend fun messages(): List<MessageDTO> =
                if (direct) store.fetchMessages(contact) else store.fetchMessages(live.token.radioId, 0u)
            suspend fun target() {
                poll(harness, live, direct, if (direct) "Hello" else "Alice: Hello", timestamp)
                assertEquals(listOf("Hello"), messages().map { it.text })
                val targets = messages()
                assertEquals(1, targets.size,
                    "stored target after original polling (direct=$direct, pending=$pending, hash=$expectedHash)")
                val saved = targets.single()
                assertEquals(timestamp, saved.timestamp)
                assertEquals(timestamp, saved.reactionTimestamp)
                assertTrue(!saved.timestampCorrected)
            }
            suspend fun reaction() {
                poll(harness, live, direct, if (direct) wireText else "Bob: $wireText", timestamp + 1u)
            }
            if (pending) {
                reaction()
                assertTrue(messages().isEmpty())
                target()
            } else {
                target()
                reaction()
            }
            val targets = messages()
            assertEquals(1, targets.size,
                "stored target after reaction polling (direct=$direct, pending=$pending, hash=$expectedHash)")
            val savedTarget = targets.single()
            val message = EntityKey(live.token.radioId, savedTarget.id)
            val reactions = store.fetchReactions(message)
            assertEquals(1, reactions.size,
                "stored reaction (direct=$direct, pending=$pending, hash=$expectedHash, " +
                    "gaps=${live.reportedGaps}, diagnostics=${harness.messagingDiagnostics})")
            val savedReaction = reactions.single()
            assertEquals(savedTarget.id, savedReaction.messageID)
            assertEquals(live.token.radioId, savedReaction.radioId)
            assertEquals(emoji, savedReaction.emoji)
            assertEquals("Bob", savedReaction.senderName)
            assertEquals(wireText, savedReaction.rawText)
            assertEquals(expectedHash, savedReaction.messageHash)
            assertEquals(if (direct) peer.id else null, savedReaction.contactID)
            assertEquals(if (direct) null else 0u.toUByte(), savedReaction.channelIndex)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(message)).reactionSummary)
            assertTrue(live.reportedGaps.isEmpty())
        } finally {
            harness.container.close()
        }
    }

    private suspend fun poll(
        harness: ContainerHarness, live: RadioSessionContainer, direct: Boolean, text: String, stamp: UInt,
    ) {
        val radio = harness.links.radios.last()
        val prior = radio.respond
        val frame = if (direct) {
            Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) + le32(stamp.toLong()) + Bytes.utf8(text)
        } else {
            Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(stamp.toLong()) + Bytes.utf8(text)
        }
        val frames = ConcurrentLinkedQueue(listOf(frame))
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a)) else prior(command)
        }
        try {
            assertEquals(1L, live.messagePollingService.pollAllMessages())
        } finally {
            radio.respond = prior
        }
    }
}
