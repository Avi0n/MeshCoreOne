// AndroidOnly: WP-308 Composer state machine: UTF-8 byte limit, send button states, cooldown, restore-on-failure, mentions, inserts.
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.Scenario
import com.meshcoreone.android.feature.chats.list.support.scenario
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import org.junit.Test

class ComposerStateHolderTest {
    private val alice = Fixtures.contact("Alice")
    private val channel = Fixtures.channel("Ops", index = 2u)

    private class Rig(
        val holder: ComposerStateHolder,
        val port: FakeSendPort,
        val timer: ManualTimer,
        val env: FakeEnvironment,
        val drafts: MemoryDrafts,
        val failures: MutableList<Throwable>,
    ) {
        val state get() = holder.state.value
    }

    private fun Scenario.rig(
        target: ComposerTarget,
        env: FakeEnvironment = FakeEnvironment(),
        drafts: MemoryDrafts = MemoryDrafts(),
    ): Rig {
        val port = FakeSendPort(Fixtures.radio)
        val timer = ManualTimer()
        val failures = ArrayList<Throwable>()
        val holder = ComposerStateHolder(target, port, drafts, env, scope, timer, { _, f -> failures += f }, java.util.Locale.US)
        return Rig(holder, port, timer, env, drafts, failures)
    }

    @Test
    fun `direct limit is 150 bytes and channel limit subtracts the node name bytes`() = scenario {
        val dm = rig(ComposerTarget.Direct(alice))
        assertEquals(150, dm.state.maxBytes)
        val env = FakeEnvironment(device = ComposerDeviceInfo("日本語", Bytes(ByteArray(32))))
        val ch = rig(ComposerTarget.Channel(channel), env)
        assertEquals(128, ch.state.maxBytes) // 139 - 2 - 9 bytes
        env.device.value = ComposerDeviceInfo("Base", Bytes(ByteArray(32)))
        assertEquals(133, ch.state.maxBytes)
    }

    @Test
    fun `counter counts UTF-8 bytes not characters and shows within 20 bytes of the limit`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.holder.onDraftChanged("a".repeat(129))
        assertFalse(rig.state.showCounter)
        rig.holder.onDraftChanged("a".repeat(130))
        assertTrue(rig.state.showCounter)
        rig.holder.onDraftChanged("日".repeat(50)) // 150 bytes, 50 characters
        assertEquals(150, rig.state.byteCount)
        assertFalse(rig.state.isOverLimit)
        rig.holder.onDraftChanged("日".repeat(50) + "a")
        assertTrue(rig.state.isOverLimit)
        assertEquals(1, rig.state.bytesOverLimit)
        assertFalse(rig.state.canSend)
        assertEquals(SendHint.OVER_LIMIT, rig.state.sendHint)
    }

    @Test
    fun `send hint follows the iOS precedence and requires a ready radio`() = scenario {
        val env = FakeEnvironment(connection = DeviceConnectionState.CONNECTING)
        val rig = rig(ComposerTarget.Direct(alice), env)
        rig.holder.onDraftChanged("hi")
        assertEquals(SendHint.REQUIRES_CONNECTION, rig.state.sendHint)
        assertFalse(rig.holder.send())
        env.connectionState.value = DeviceConnectionState.READY
        assertEquals(SendHint.TAP_TO_SEND, rig.state.sendHint)
        rig.holder.onDraftChanged("  ​\n ")
        assertEquals(SendHint.TYPE_FIRST, rig.state.sendHint)
        assertFalse(rig.state.canSend)
    }

    @Test
    fun `send clears the field, sends the trimmed text once and re-enables after one second`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.holder.onDraftChanged("  hello  ")
        assertTrue(rig.holder.send())
        assertEquals("", rig.state.draft)
        assertNull(rig.drafts.values[ComposerTarget.Direct(alice).conversationId])
        assertEquals(listOf("createPendingDirect:hello", "enqueueDM"), rig.port.calls)
        assertEquals(DirectMessageEnvelope(rig.port.pendingMessages.single().id, alice.id), rig.port.directEnvelopes.single())
        assertTrue(rig.port.channelEnvelopes.isEmpty())
        assertEquals(listOf(1_000L), rig.timer.requested)
        rig.holder.onDraftChanged("again")
        assertFalse(rig.state.canSend, "cooling down")
        assertFalse(rig.holder.send())
        rig.timer.advance()
        assertTrue(rig.state.canSend)
        assertEquals(ComposerSendPhase.Idle, rig.state.sendPhase)
    }

    @Test
    fun `a second send while the first is in flight is rejected even after the cooldown`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.port.sendGate = CompletableDeferred()
        rig.holder.onDraftChanged("one")
        assertTrue(rig.holder.send())
        rig.timer.advance()
        rig.holder.onDraftChanged("two")
        assertIs<ComposerSendPhase.Sending>(rig.state.sendPhase)
        assertFalse(rig.holder.send())
        rig.port.sendGate!!.complete(Unit)
        assertEquals(ComposerSendPhase.Idle, rig.state.sendPhase)
        assertEquals(1, rig.port.calls.count { it.startsWith("createPending") })
    }

    @Test
    fun `channel sends go through the channel pending path with the slot index`() = scenario {
        val rig = rig(ComposerTarget.Channel(channel))
        rig.holder.onDraftChanged("net check")
        rig.holder.send()
        assertEquals(listOf("createPendingChannel:2:net check", "enqueueChannel"), rig.port.calls)
        val pending = rig.port.pendingMessages.single()
        assertEquals(ChannelMessageEnvelope(pending.id, 2u, false, pending.text, pending.timestamp, null),
            rig.port.channelEnvelopes.single())
        assertTrue(rig.port.directEnvelopes.isEmpty())
    }

    @Test
    fun `channel envelope captures current node identity after pending creation`() = scenario {
        val env = FakeEnvironment(device = ComposerDeviceInfo("Before", Bytes(ByteArray(32))))
        val rig = rig(ComposerTarget.Channel(channel), env)
        rig.port.createGate = CompletableDeferred()
        rig.holder.onDraftChanged("  original \u4e2d\uD83D\uDE80 text  ")
        assertTrue(rig.holder.send())
        assertTrue(rig.port.channelEnvelopes.isEmpty())
        env.device.value = ComposerDeviceInfo("After", Bytes(ByteArray(32)))
        rig.port.createGate!!.complete(Unit)
        val pending = rig.port.pendingMessages.single()
        assertEquals("original \u4e2d\uD83D\uDE80 text", pending.text)
        assertEquals(channel.radioId, pending.radioId)
        assertEquals(channel.index, pending.channelIndex)
        assertEquals(ChannelMessageEnvelope(pending.id, channel.index, false, pending.text, pending.timestamp, "After"),
            rig.port.channelEnvelopes.single())
    }

    @Test
    fun `enqueue acceptance does not fabricate wire delivery or create another message`() = scenario {
        for (target in listOf<ComposerTarget>(ComposerTarget.Direct(alice), ComposerTarget.Channel(channel))) {
            val rig = rig(target)
            rig.port.sendGate = CompletableDeferred()
            rig.holder.onDraftChanged("hello")
            assertTrue(rig.holder.send())
            assertIs<ComposerSendPhase.Sending>(rig.state.sendPhase)
            val pending = rig.port.pendingMessages.single()
            assertEquals(MessageStatus.PENDING, pending.status)
            rig.port.sendGate!!.complete(Unit)
            assertEquals(ComposerSendPhase.Idle, rig.state.sendPhase)
            assertEquals(listOf(pending), rig.port.pendingMessages)
            assertEquals(MessageStatus.PENDING, pending.status)
            assertEquals(1, rig.port.directEnvelopes.size + rig.port.channelEnvelopes.size)
        }
    }

    @Test
    fun `typed queue failures retain saved pending state and current draft for both targets`() = scenario {
        val failures = listOf(
            ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected) to ComposerSendFailure.NOT_CONNECTED,
            ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(IllegalStateException("synthetic store failure"))) to ComposerSendFailure.OTHER,
        )
        for (target in listOf<ComposerTarget>(ComposerTarget.Direct(alice), ComposerTarget.Channel(channel))) {
            for ((failure, reason) in failures) {
                val rig = rig(target)
                rig.port.sendGate = CompletableDeferred()
                rig.port.sendFailure = failure
                rig.holder.onDraftChanged("saved pending")
                assertTrue(rig.holder.send())
                rig.holder.onDraftChanged("new draft")
                rig.port.sendGate!!.complete(Unit)
                assertEquals(ComposerSendPhase.Failed(reason, messageSaved = true), rig.state.sendPhase)
                assertEquals("new draft", rig.state.draft)
                assertEquals("new draft", rig.drafts.values[target.conversationId])
                assertEquals("saved pending", rig.port.pendingMessages.single().text)
                assertEquals(listOf(failure), rig.failures)
            }
        }
    }

    @Test
    fun `delayed creation failure never overwrites a newly typed draft or enqueues`() = scenario {
        for (target in listOf<ComposerTarget>(ComposerTarget.Direct(alice), ComposerTarget.Channel(channel))) {
            val rig = rig(target)
            rig.port.createGate = CompletableDeferred()
            rig.port.createFailure = MessageServiceException(MessageServiceError.NotConnected)
            rig.holder.onDraftChanged("captured draft")
            assertTrue(rig.holder.send())
            rig.holder.onDraftChanged("new draft")
            rig.port.createGate!!.complete(Unit)
            assertEquals("new draft", rig.state.draft)
            assertEquals("new draft", rig.drafts.values[target.conversationId])
            assertEquals(ComposerSendPhase.Failed(ComposerSendFailure.NOT_CONNECTED, messageSaved = false), rig.state.sendPhase)
            assertTrue(rig.port.pendingMessages.isEmpty())
            assertTrue(rig.port.directEnvelopes.isEmpty() && rig.port.channelEnvelopes.isEmpty())
            assertEquals(1, rig.failures.size)
        }
    }

    @Test
    fun `enqueue cancellation is neither success nor failure and does not duplicate the saved pending draft`() = scenario {
        for (target in listOf<ComposerTarget>(ComposerTarget.Direct(alice), ComposerTarget.Channel(channel))) {
            val rig = rig(target)
            rig.port.sendFailure = CancellationException("synthetic observer cancellation")
            rig.holder.onDraftChanged("saved pending")
            assertTrue(rig.holder.send())
            assertTrue(rig.failures.isEmpty())
            assertIs<ComposerSendPhase.Sending>(rig.state.sendPhase)
            assertEquals("", rig.state.draft)
            assertNull(rig.drafts.values[target.conversationId])
            assertEquals("saved pending", rig.port.pendingMessages.single().text)
            assertEquals(1, rig.port.directEnvelopes.size + rig.port.channelEnvelopes.size)
        }
    }

    @Test
    fun `failure before a pending row exists restores the draft unless the user typed again`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.port.createFailure = MessageServiceException(MessageServiceError.NotConnected)
        rig.holder.onDraftChanged("keep me")
        rig.holder.send()
        assertEquals("keep me", rig.state.draft)
        assertEquals(ComposerSendPhase.Failed(ComposerSendFailure.NOT_CONNECTED, messageSaved = false), rig.state.sendPhase)
        assertEquals("keep me", rig.drafts.values[ComposerTarget.Direct(alice).conversationId])
        assertEquals(1, rig.failures.size)
    }

    @Test
    fun `failure after the pending row exists leaves the draft empty and defers retry to the timeline`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.port.sendFailure = MessageServiceException(MessageServiceError.SendFailed("radio busy"))
        rig.holder.onDraftChanged("hello")
        rig.holder.send()
        assertEquals("", rig.state.draft)
        assertEquals(ComposerSendPhase.Failed(ComposerSendFailure.OTHER, messageSaved = true), rig.state.sendPhase)
        rig.holder.onDraftChanged("x")
        assertEquals(ComposerSendPhase.Idle, rig.state.sendPhase, "typing clears the failure banner")
    }

    @Test
    fun `cancellation is not reported as a send failure`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.port.sendFailure = CancellationException("screen closed")
        rig.holder.onDraftChanged("hello")
        rig.holder.send()
        assertTrue(rig.failures.isEmpty())
        assertFalse(rig.state.sendPhase is ComposerSendPhase.Failed)
    }

    @Test
    fun `typing an at-query offers chat contacts and selecting one inserts the mesh name`() = scenario {
        val bob = Fixtures.contact("Bob's Solar Node", nickname = "Bobby")
        val repeater = Fixtures.contact("Bobrepeater", typeRawValue = 2u)
        val env = FakeEnvironment(contacts = listOf(alice, bob, repeater))
        val rig = rig(ComposerTarget.Direct(alice), env)
        rig.holder.onDraftChanged("hey @bo")
        assertEquals("bo", rig.state.mentionQuery)
        assertEquals(listOf(bob), rig.state.mentionSuggestions) // nickname Bobby is the display name; query matches "Bobby"
        rig.holder.selectMention(bob)
        assertEquals("hey @[Bob's Solar Node] ", rig.state.draft)
        assertNull(rig.state.mentionQuery)
        assertEquals(1, rig.state.focusRequest)
    }

    @Test
    fun `mention suggestions are capped at 20 and ordered by recent senders`() = scenario {
        val contacts = (1..30).map { Fixtures.contact("N%02d".format(it)) }
        val env = FakeEnvironment(contacts = contacts)
        env.recentSenderOrder.value = mapOf("N30" to 9u, "N29" to 8u)
        val rig = rig(ComposerTarget.Direct(alice), env)
        rig.holder.onDraftChanged("@")
        assertEquals(20, rig.state.mentionSuggestions.size)
        assertEquals(listOf("N30", "N29", "N01"), rig.state.mentionSuggestions.take(3).map { it.name })
    }

    @Test
    fun `shared text and mentions append with a single separating space`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.holder.onDraftChanged("meet")
        rig.holder.insertShared("37.000000, -122.000000")
        assertEquals("meet 37.000000, -122.000000", rig.state.draft)
        rig.holder.onDraftChanged("hi ")
        rig.holder.appendMention("Alice")
        assertEquals("hi @[Alice] ", rig.state.draft)
    }

    @Test
    fun `insert at selection replaces the selection and returns the caret`() = scenario {
        val rig = rig(ComposerTarget.Direct(alice))
        rig.holder.onDraftChanged("ab cd")
        assertEquals(3, rig.holder.insertAtSelection("😀", 4, 1))
        assertEquals("a😀d", rig.state.draft)
    }

    @Test
    fun `drafts persist and restore per conversation`() = scenario {
        val id = ComposerTarget.Direct(alice).conversationId
        val rig = rig(ComposerTarget.Direct(alice), drafts = MemoryDrafts(mapOf(id to "saved @al")))
        assertEquals("saved @al", rig.state.draft)
        rig.holder.onDraftChanged("")
        assertNull(rig.drafts.values[id])
    }

    @Test
    fun `share location prefers a fresh fix then the phone fix then the node`() = scenario {
        val node = ComposerDeviceInfo("Base", Bytes(ByteArray(32)), 1.0, 2.0, hasLocation = true)
        val location = FakeLocation(isAuthorized = true, currentCoordinate = Coordinate(3.0, 4.0), fresh = Coordinate(5.0, 6.0))
        val rig = rig(ComposerTarget.Direct(alice), FakeEnvironment(device = node, location = location))
        rig.holder.shareLocation()
        assertEquals("5.000000, 6.000000", rig.state.draft)
        location.fresh = null
        rig.holder.onDraftChanged("")
        rig.holder.shareLocation()
        assertEquals("3.000000, 4.000000", rig.state.draft)
        location.isAuthorized = false
        rig.holder.onDraftChanged("")
        rig.holder.shareLocation()
        assertEquals("1.000000, 2.000000", rig.state.draft)
        assertEquals(2, location.requests, "no request without authorization")
    }

    @Test
    fun `a superseded location request is cancelled and inserts nothing`() = scenario {
        val gate = CompletableDeferred<Unit>()
        val location = FakeLocation(isAuthorized = true, fresh = Coordinate(5.0, 6.0), gate = gate)
        val rig = rig(ComposerTarget.Direct(alice), FakeEnvironment(location = location))
        val first = rig.holder.shareLocation()
        rig.holder.shareLocation()
        assertTrue(first.isCancelled)
        gate.complete(Unit)
        assertEquals("5.000000, 6.000000", rig.state.draft)
    }

    @Test
    fun `share my info inserts the contact token only for a full key and named node`() = scenario {
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        val env = FakeEnvironment(device = ComposerDeviceInfo("Base 1", key))
        val rig = rig(ComposerTarget.Direct(alice), env)
        assertTrue(rig.holder.canShareMyInfo())
        rig.holder.shareMyInfo()
        assertEquals("<${"AB".repeat(32)}:1:Base 1>", rig.state.draft)
        env.device.value = ComposerDeviceInfo("  ", key)
        assertFalse(rig.holder.canShareMyInfo())
    }
}
