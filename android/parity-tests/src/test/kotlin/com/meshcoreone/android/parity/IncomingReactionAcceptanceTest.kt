// AndroidOnly: WP-501 Real Room and normal polling witnesses for frozen incoming reaction consumers.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.EPOCH
import com.meshcoreone.android.app.container.RadioSessionContainer
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.container.key
import com.meshcoreone.android.app.container.le32
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
class IncomingReactionAcceptanceTest : RoomProcessTest() {
    private val thumbsUp = "\uD83D\uDC4D"
    private val peerKey = key(3)
    private val stamp = 1_704_067_200u

    @Test fun knownPocketChannelControlPersistsTheReactionThroughActualDatabaseFallback() = runTest {
        assertReaction(this, direct = false, pocket(), pending = false, expectedHash = "b45pc4ek", requireForwarding = false)
    }

    @Test fun knownPocketDirectControlPersistsTheReactionThroughActualDatabaseFallback() = runTest {
        assertReaction(this, direct = true, pocket(direct = true), pending = false, expectedHash = "b45pc4ek", requireForwarding = false)
    }

    @Test fun mountedPocketChannelReactionSurvivesArrivalBeforeItsTarget() = runTest {
        assertReaction(this, direct = false, pocket(), pending = true, expectedHash = "b45pc4ek")
    }

    @Test fun mountedPocketDirectReactionSurvivesArrivalBeforeItsTarget() = runTest {
        assertReaction(this, direct = true, pocket(direct = true), pending = true, expectedHash = "b45pc4ek")
    }

    @Test fun mountedMeshCoreOpenV3ChannelReactionReachesTheActualStoredTarget() = runTest {
        assertReaction(this, direct = false, "r:aa61:00", pending = false, expectedHash = "aa61")
    }

    @Test fun mountedMeshCoreOpenV3DirectReactionReachesTheActualStoredTarget() = runTest {
        assertReaction(this, direct = true, "r:92c7:00", pending = false, expectedHash = "92c7")
    }

    @Test fun mountedMeshCoreOpenV1ChannelReactionReachesTheActualStoredTarget() = runTest {
        assertReaction(this, direct = false, v1(), pending = false, expectedHash = v1Hash())
    }

    @Test fun mountedMeshCoreOpenV1DirectReactionReachesTheActualStoredTarget() = runTest {
        assertReaction(this, direct = true, v1(), pending = false, expectedHash = v1Hash())
    }

    @Test fun mountedMeshCoreOpenV3ChannelReactionIsIndependentOfTheSchedulerEpoch() = runTest {
        testScheduler.advanceTimeBy(301_000)
        assertReaction(this, direct = false, "r:aa61:00", pending = false, expectedHash = "aa61")
    }

    @Test fun mountedMeshCoreOpenV3DirectReactionIsIndependentOfTheSchedulerEpoch() = runTest {
        testScheduler.advanceTimeBy(301_000)
        assertReaction(this, direct = true, "r:92c7:00", pending = false, expectedHash = "92c7")
    }

    private fun pocket(direct: Boolean = false) =
        (if (direct) "" else "@[Alice]") + thumbsUp + "\nb45pc4ek"

    private fun v1() = "r:1704067200000_210078619_828172268:$thumbsUp"
    private fun v1Hash() = "1704067200_210078619_828172268"

    private suspend fun assertReaction(
        test: TestScope,
        direct: Boolean,
        reactionText: String,
        pending: Boolean,
        expectedHash: String,
        requireForwarding: Boolean = true,
    ) {
        // Room work may fast-forward virtual timers; only wire arrivals advance this fixture's wall clock.
        var receiveTime = EPOCH
        val syncClock = object : SyncClock {
            override fun now() = receiveTime
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
            assertEquals(EPOCH.epochSecond.toUInt(), stamp)
            harness.manager.connect(harness.target())
            harness.eventually("mounted reaction session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            assertEquals(secret, store.fetchChannels(live.token.radioId).single().secret)
            val peer = ContactDTO(radioId = live.token.radioId, publicKey = peerKey, name = "Bob", lastHeardTimestamp = null)
            store.saveContact(peer)
            val contact = EntityKey(live.token.radioId, peer.id)
            suspend fun rows(): List<MessageDTO> =
                if (direct) store.fetchMessages(contact) else store.fetchMessages(live.token.radioId, 0u)
            suspend fun pollTarget() {
                poll(harness, live, direct, if (direct) "Hello" else "Alice: Hello", stamp)
                assertEquals(listOf("Hello"), rows().map { it.text })
                val saved = rows().single()
                assertEquals(stamp, saved.timestamp)
                assertEquals(stamp, saved.reactionTimestamp)
                assertTrue(!saved.timestampCorrected)
            }
            suspend fun reaction() {
                receiveTime = EPOCH.plusSeconds(1)
                poll(harness, live, direct, if (direct) reactionText else "Bob: $reactionText", stamp + 1u)
            }
            if (pending) {
                reaction()
                assertTrue(rows().isEmpty(), "A pending reaction is consumed, not stored as an ordinary message")
                pollTarget()
            } else {
                pollTarget()
                reaction()
            }
            assertEquals(EPOCH.plusSeconds(1), syncClock.now())
            val target = assertNotNull(rows().firstOrNull { it.text == "Hello" })
            val message = EntityKey(live.token.radioId, target.id)
            val reactions = store.fetchReactions(message)
            assertEquals(
                1, reactions.size,
                "The mounted consumer must retain the actual incoming reaction " +
                    "(wall=${syncClock.now()}, schedulerMillis=${test.testScheduler.currentTime}, " +
                    "targetTimestamp=${target.reactionTimestamp})",
            )
            val saved = reactions.single()
            assertEquals(target.id, saved.messageID)
            assertEquals(live.token.radioId, saved.radioId)
            assertEquals(thumbsUp, saved.emoji)
            assertEquals("Bob", saved.senderName)
            assertEquals(reactionText, saved.rawText)
            assertEquals(expectedHash, saved.messageHash)
            assertEquals(if (direct) peer.id else null, saved.contactID)
            assertEquals(if (direct) null else 0u.toUByte(), saved.channelIndex)
            assertEquals("$thumbsUp:1", assertNotNull(store.fetchMessage(message)).reactionSummary)
            assertEquals(listOf(target.id), rows().map { it.id })
            if (requireForwarding) {
                assertTrue(live.reportedGaps.none {
                    it == com.meshcoreone.android.app.container.ServiceVisibilityGaps.REACTION_TARGET_LOOKUP ||
                        it == com.meshcoreone.android.app.container.ServiceVisibilityGaps.REACTION_PENDING_QUEUE ||
                        it == com.meshcoreone.android.app.container.ServiceVisibilityGaps.MESHCORE_OPEN_REACTIONS
                }, "Real reaction services must be forwarded, not replaced with degraded success")
            }
        } finally {
            harness.container.close()
        }
    }

    private suspend fun poll(
        harness: ContainerHarness,
        live: RadioSessionContainer,
        direct: Boolean,
        text: String,
        timestamp: UInt,
    ) {
        val radio = harness.links.radios.last()
        val prior = radio.respond
        val frame = if (direct) {
            Bytes.of(0x10, 20, 0, 0) + peerKey.prefix(6) + Bytes.of(0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
        } else {
            Bytes.of(0x11, 20, 0, 0, 0, 0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text)
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
