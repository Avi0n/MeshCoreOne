// AndroidOnly: WP-214 Strict wall-timestamp oracle controls and independent causal-completion evidence.
package com.meshcoreone.android.core.services.sync

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** A fine-resolution test clock, not a guarantee about real wall-clock observations. */
internal class ReadAdvancingSyncClock(private val delegate: SyncClock) : SyncClock by delegate {
    private var reads = 0L

    @Synchronized
    override fun now(): Instant = delegate.now().plusNanos(reads++)
}

private class WallReadSequence(delegate: SyncClock, vararg instants: Instant) : SyncClock by delegate {
    private val instants = ArrayDeque(instants.toList())

    override fun now(): Instant {
        check(instants.isNotEmpty()) { "Unexpected wall-clock read" }
        return instants.removeFirst()
    }
}

class SyncActivityOrderTests {
    @TestFactory
    fun timestampPredicate(): List<DynamicTest> = listOf(
        nativeCase("activity timestamp predicate rejects two missing observations") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock))
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate rejects a missing poll observation") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH))
            tracker.recordActivityEnded()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate rejects a missing end observation") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH))
            tracker.pollAllMessages()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate rejects equal dates despite causal end before poll") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH, Instant.EPOCH))
            tracker.recordActivityEnded()
            tracker.pollAllMessages()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate rejects backward dates despite causal end before poll") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH.plusSeconds(1), Instant.EPOCH))
            tracker.recordActivityEnded()
            tracker.pollAllMessages()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate rejects poll then end on increasing wall time") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)))
            tracker.pollAllMessages()
            tracker.recordActivityEnded()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate accepts strictly increasing end then poll dates") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)))
            tracker.recordActivityEnded()
            tracker.pollAllMessages()
            assertTrue(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate uses the latest end date rather than the first event") {
            val tracker = OrderTrackingMessagePollingService(
                WallReadSequence(clock, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), Instant.EPOCH.plusSeconds(2)),
            )
            tracker.recordActivityEnded()
            tracker.pollAllMessages()
            assertTrue(tracker.activityEndedBeforeMessagePoll)
            tracker.recordActivityEnded()
            assertFalse(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("activity timestamp predicate accepts rollback after poll without a causal guard") {
            val tracker = OrderTrackingMessagePollingService(WallReadSequence(clock, Instant.EPOCH.plusSeconds(1), Instant.EPOCH))
            tracker.pollAllMessages()
            tracker.recordActivityEnded()
            assertTrue(tracker.activityEndedBeforeMessagePoll)
        },
        nativeCase("read advancing fixture advances generic wall reads but delegates elapsed time and sleep") {
            val fixture = ReadAdvancingSyncClock(clock)
            val origin = clock.now()
            val elapsed = clock.elapsed()
            assertEquals(origin, fixture.now())
            assertEquals(origin.plusNanos(1), fixture.now())
            assertEquals(origin.plusNanos(2), fixture.now())
            assertEquals(elapsed, fixture.elapsed())
            fixture.sleep(5.milliseconds)
            assertEquals(elapsed + 5.milliseconds, fixture.elapsed())
            assertEquals(clock.now().plusNanos(3), fixture.now())
        },
    )

    @TestFactory
    fun causalCompletion(): List<DynamicTest> = listOf(
        nativeCase("native sync awaits one ended callback completion before polling with frozen wall time") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val tracker = OrderTrackingMessagePollingService(clock)
            val callbackEntered = CompletableDeferred<Unit>()
            val releaseCallback = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var ended = 0
            val polling = object : MockMessagePollingService() {
                override suspend fun pollAllMessages(): Long {
                    events += "poll"
                    return tracker.pollAllMessages()
                }
            }
            coordinator.setSyncActivityCallbacks({}, {
                ended += 1
                tracker.recordActivityEnded()
                callbackEntered.complete(Unit)
                releaseCallback.await()
                events += "ended.complete"
            }, {})
            val sync = task {
                coordinator.performFullSync(
                    radioId, SyncInMemoryStore.createTestDataStore(radioId),
                    MockContactService(), MockChannelService(), polling,
                )
            }
            callbackEntered.await()
            settle()
            assertEquals(1, ended)
            assertEquals(0, tracker.pollAllMessagesCallCount)
            assertTrue(events.isEmpty())
            assertFalse(sync.isCompleted)
            releaseCallback.complete(Unit)
            assertTrue(sync.await().isConnectionUsable)
            assertEquals(SyncState.Synced, coordinator.state)
            assertEquals(1, ended)
            assertEquals(1, tracker.pollAllMessagesCallCount)
            assertEquals(listOf("ended.complete", "poll"), events)
            assertFalse(tracker.activityEndedBeforeMessagePoll, "Equal wall dates are not strict timestamp evidence")
        },
    )
}
