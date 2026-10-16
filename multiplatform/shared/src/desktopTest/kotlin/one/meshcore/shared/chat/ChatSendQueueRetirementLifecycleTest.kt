package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertFreshReplay
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertParked
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.withFixture
import one.meshcore.shared.connection.DeviceConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatSendQueueRetirementLifecycleTest {
    @Test
    fun aDirectHydrationReadReleasedAfterShutdownCannotRestartTheOldDrain() = heldHydrate(false)

    @Test
    fun aChannelHydrationReadReleasedAfterShutdownCannotRestartTheOldDrain() = heldHydrate(true)

    @Test
    fun anAlreadyPersistedDirectSignalAfterShutdownLeavesTheIntentParked() = withFixture(channel = false) {
        it.seedPending()
        it.start { it.queue.shutdown() }.await()
        it.start { it.queue.signalDMEnqueued(DirectMessageEnvelope(it.message.id, ChatFixtures.CONTACT)) }.await()
        it.awaitOldDrain()
        assertParked(it, "late-persisted-direct-signal")
        assertFreshReplay(it)
    }

    @Test
    fun aLateConnectionObserverDoesNotSubscribeAfterShutdown() = withFixture(channel = false) {
        it.start { it.queue.shutdown() }.await()
        val subscribed = CompletableDeferred<Unit>()
        val states = flow {
            subscribed.complete(Unit)
            emit(DeviceConnectionState.READY)
            awaitCancellation()
        }
        it.start { it.queue.observeConnectionState(DeviceConnectionState.READY, states) }.await()
        it.flush()
        assertFalse(subscribed.isCompleted, "retirement must prevent a new observer job")
        assertEquals(0, it.snapshot("late-observer").oldWrites)
    }

    @Test
    fun aLateTransportSignalCannotArmARetiredQueue() = withFixture(channel = false) { fixture ->
        fixture.start { fixture.queue.shutdown() }.await()
        val entered = CompletableDeferred<Unit>()
        val waiter = fixture.start {
            entered.complete(Unit)
            fixture.queue.triggers.wait()
        }
        try {
            entered.await()
            fixture.start { fixture.queue.transportDidOpen() }.await()
            fixture.flush()
            assertFalse(waiter.isCompleted, "retirement must prevent a new wake-signal job")
        } finally {
            waiter.cancelAndJoin()
        }
    }

    @Test
    fun aWarmUpReleasedAfterRetirementDoesNotBeginTheNextPurge() = withFixture(channel = false) { fixture ->
        val gate = fixture.holdWarmUp()
        val warming = fixture.start { fixture.queue.warmUp() }
        try {
            gate.entered.await()
            fixture.start { fixture.queue.shutdown() }.await()
            gate.release.complete(Unit)
            warming.await()
            assertEquals(0, fixture.store.legacyPurgeCalls)
            ChatSendQueueRetirementAssertions.assertForeignUnchanged(fixture, fixture.snapshot("late-warm-up"))
        } finally {
            gate.release.complete(Unit)
            warming.cancelAndJoin()
        }
    }

    @Test
    fun repeatedShutdownKeepsBothTheQueueAndItsDurableIntentRetired() = withFixture(channel = false) {
        it.seedPending()
        it.start { it.queue.shutdown() }.await()
        it.start { it.queue.shutdown() }.await()
        it.start { it.queue.hydrate() }.await()
        it.awaitOldDrain()
        assertParked(it, "idempotent-shutdown")
        assertFreshReplay(it)
    }

    private fun heldHydrate(channel: Boolean) = withFixture(channel) { fixture ->
        fixture.seedPending()
        val gate = fixture.holdHydrate()
        val hydrating = fixture.start { fixture.queue.hydrate() }
        try {
            gate.entered.await()
            fixture.start { fixture.queue.shutdown() }.await()
            gate.release.complete(Unit)
            hydrating.await()
            fixture.awaitOldDrain()
            assertParked(fixture, "released-hydrate-channel=$channel")
            assertFreshReplay(fixture)
        } finally {
            gate.release.complete(Unit)
            hydrating.cancelAndJoin()
        }
    }
}
