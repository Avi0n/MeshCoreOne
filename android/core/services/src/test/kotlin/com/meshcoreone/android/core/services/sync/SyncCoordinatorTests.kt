// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.notifications.NotificationHarness
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal const val SYNC_SUITE = "SyncCoordinatorTests"

internal fun newRadio() = RadioId(UUID.randomUUID())

/** Original SyncCoordinatorTests: state model, notifications, full-sync phases and lifecycle. */
class SyncCoordinatorTests {
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(SYNC_SUITE, name, body)

    @TestFactory
    fun stateAndLifecycle(): List<DynamicTest> = listOf(
        pureCase(SYNC_SUITE, "SyncState cases are distinct") {
            val idle: SyncState = SyncState.Idle
            val syncing: SyncState = SyncState.Syncing(SyncProgress(SyncPhase.CONTACTS, 0, 0))
            val synced: SyncState = SyncState.Synced
            val failed: SyncState = SyncState.Failed(SyncCoordinatorError.NotConnected())
            assertNotEquals(idle, syncing)
            assertNotEquals(syncing, synced)
            assertNotEquals(synced, failed)
        },
        pureCase(SYNC_SUITE, "SyncProgress initializes correctly") {
            val progress = SyncProgress(SyncPhase.CONTACTS, 5, 10)
            assertEquals(SyncPhase.CONTACTS, progress.phase)
            assertEquals(5, progress.current)
            assertEquals(10, progress.total)
        },
        pureCase(SYNC_SUITE, "SyncPhase has all expected cases") {
            assertEquals(listOf(SyncPhase.CONTACTS, SyncPhase.CHANNELS, SyncPhase.MESSAGES), SyncPhase.entries.toList())
        },
        case("SyncCoordinator initializes with idle state") {
            val coordinator = coordinator()
            assertEquals(SyncState.Idle, coordinator.state)
            assertEquals(0, coordinator.contactsVersion)
            assertEquals(0, coordinator.conversationsVersion)
            assertNull(coordinator.lastSyncDate)
        },
        case("notifyContactsChanged increments contactsVersion") {
            val coordinator = coordinator()
            val initial = coordinator.contactsVersion
            coordinator.notifyContactsChanged()
            assertEquals(initial + 1, coordinator.contactsVersion)
        },
        case("notifyConversationsChanged increments conversationsVersion") {
            val coordinator = coordinator()
            val initial = coordinator.conversationsVersion
            coordinator.notifyConversationsChanged()
            assertEquals(initial + 1, coordinator.conversationsVersion)
        },
        case("Multiple notifications increment correctly") {
            val coordinator = coordinator()
            coordinator.notifyContactsChanged()
            coordinator.notifyContactsChanged()
            coordinator.notifyConversationsChanged()
            assertEquals(2, coordinator.contactsVersion)
            assertEquals(1, coordinator.conversationsVersion)
        },
        case("Sync activity callbacks fire during full sync") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val started = CallTracker()
            val ended = CallTracker()
            coordinator.setSyncActivityCallbacks({ started.markCalled() }, { ended.markCalled() }, {})
            coordinator.performFullSync(radioId, store, MockContactService(), MockChannelService(), MockMessagePollingService())
            assertTrue(started.wasCalled, "onSyncActivityStarted should have been called")
            assertTrue(ended.wasCalled, "onSyncActivityEnded should have been called")
        },
        case("Channel phase failure is partial and keeps connection usable") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val failure = ChannelServiceError.CircuitBreakerOpen(consecutiveFailures = 3)
            val channels = MockChannelService().apply { stubbedSyncChannelsResult = Result.failure(failure) }
            val polling = MockMessagePollingService()
            val result = coordinator.performFullSync(radioId, store, MockContactService(), channels, polling)
            assertEquals(3, failure.consecutiveFailures)
            assertEquals(1, channels.syncChannelsInvocations.size)
            assertEquals(SyncPhaseStatus.Clean, result.contacts)
            assertEquals(SyncPhaseStatus.Partial, result.channels)
            assertEquals(SyncPhaseStatus.Clean, result.messages)
            assertTrue(result.isConnectionUsable)
            assertEquals(SyncState.Synced, coordinator.state)
            assertEquals(1, polling.pollAllMessagesCallCount)
        },
        case("Message polling failure does not fail contacts and channels") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val polling = MockMessagePollingService().apply {
                stubbedPollAllMessagesResult = Result.failure(SyncCoordinatorError.SyncFailed("messages saturated"))
            }
            val result = coordinator.performFullSync(radioId, store, MockContactService(), MockChannelService(), polling)
            assertEquals(SyncPhaseStatus.Clean, result.contacts)
            assertEquals(SyncPhaseStatus.Clean, result.channels)
            val messages = result.messages
            assertTrue(messages is SyncPhaseStatus.Failed, "Expected failed message phase, got $messages")
            assertTrue(messages.reason.contains("messages saturated", ignoreCase = true))
            assertTrue(result.isConnectionUsable)
            assertEquals(SyncState.Synced, coordinator.state)
        },
        case("Sync activity callbacks not double called on error") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val ended = CallTracker()
            coordinator.setSyncActivityCallbacks({}, { ended.markCalled() }, {})
            val contacts = MockContactService().apply { stubbedSyncContactsResult = Result.failure(SyncCoordinatorError.SyncFailed("Test error")) }
            assertFailsWith<SyncCoordinatorError.SyncFailed> {
                coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            }
            assertEquals(1, ended.callCount, "onSyncActivityEnded should be called exactly once on error")
        },
        case("Sync activity ends before messages phase") {
            val advancingClock = ReadAdvancingSyncClock(clock)
            val coordinator = SyncCoordinator(scope, advancingClock)
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val tracker = OrderTrackingMessagePollingService(advancingClock)
            coordinator.setSyncActivityCallbacks({}, { tracker.recordActivityEnded() }, {})
            coordinator.performFullSync(radioId, store, MockContactService(), MockChannelService(), tracker)
            assertTrue(tracker.activityEndedBeforeMessagePoll, "Activity should end before message polling starts")
        },
        case("onDisconnected clears notification suppression flag") {
            val coordinator = coordinator()
            val notifications = NotificationHarness(scope).service
            notifications.isSuppressingNotifications = true
            assertTrue(notifications.isSuppressingNotifications)
            coordinator.onDisconnected(notifications.syncSuppressionPort())
            assertFalse(notifications.isSuppressingNotifications)
        },
        case("onDisconnected resets sync state to idle") {
            val coordinator = coordinator()
            val radioId = newRadio()
            coordinator.performFullSync(
                radioId, SyncInMemoryStore.createTestDataStore(radioId),
                MockContactService(), MockChannelService(), MockMessagePollingService(),
            )
            assertEquals(SyncState.Synced, coordinator.state)
            coordinator.onDisconnected(FakeNotificationService())
            assertEquals(SyncState.Idle, coordinator.state)
        },
        case("onDisconnected calls onSyncActivityEnded when mid-sync in contacts phase") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val delaying = DelayingContactService()
            val started = CallTracker()
            val ended = CallTracker()
            coordinator.setSyncActivityCallbacks({ started.markCalled() }, { ended.markCalled() }, {})
            val sync = task { coordinator.performFullSync(radioId, store, delaying, MockChannelService(), MockMessagePollingService()) }
            waitUntil("Sync activity should have started") { started.wasCalled }
            coordinator.onDisconnected(FakeNotificationService())
            assertTrue(ended.wasCalled, "onSyncActivityEnded should be called when disconnecting mid-sync")
            delaying.completeSync()
            sync.cancel()
        },
        case("Background sync skips channel sync") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contacts = MockContactService()
            val channels = MockChannelService()
            coordinator.performFullSync(radioId, store, contacts, channels, MockMessagePollingService(), MockAppStateProvider(false))
            assertTrue(channels.syncChannelsInvocations.isEmpty(), "Channel sync should be skipped when in background")
            assertEquals(1, contacts.syncContactsInvocations.size, "Contact sync should still run in background")
        },
        case("Foreground sync includes channel sync") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contacts = MockContactService()
            val channels = MockChannelService()
            coordinator.performFullSync(radioId, store, contacts, channels, MockMessagePollingService(), MockAppStateProvider(true))
            assertEquals(1, channels.syncChannelsInvocations.size, "Channel sync should run when in foreground")
            assertEquals(1, contacts.syncContactsInvocations.size, "Contact sync should run in foreground")
        },
        case("performFullSync forwards the pipelined-read flag from config into channel sync") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val channels = MockChannelService()
            coordinator.performFullSync(
                radioId, store, MockContactService(), channels, MockMessagePollingService(), null,
                channelSyncConfig = ChannelSyncConfig(usePipelinedChannelRead = true),
            )
            assertEquals(1, channels.syncChannelsInvocations.size)
            assertEquals(true, channels.syncChannelsInvocations.last().usePipelinedRead, "Config flag should reach channel sync")
        },
        case("performFullSync defaults channel sync to the serial read path") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val channels = MockChannelService()
            coordinator.performFullSync(radioId, store, MockContactService(), channels, MockMessagePollingService(), null)
            assertEquals(1, channels.syncChannelsInvocations.size)
            assertEquals(false, channels.syncChannelsInvocations.last().usePipelinedRead, "Default config should use the serial path")
        },
        case("Nil appStateProvider defaults to foreground behavior") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val contacts = MockContactService()
            val channels = MockChannelService()
            coordinator.performFullSync(radioId, store, contacts, channels, MockMessagePollingService(), null)
            assertEquals(1, channels.syncChannelsInvocations.size, "Nil appStateProvider should default to foreground behavior")
            assertEquals(1, contacts.syncContactsInvocations.size)
        },
        case("performFullSync ignores duplicate calls when already syncing") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val delaying = DelayingContactService()
            val started = CallTracker()
            coordinator.setSyncActivityCallbacks({ started.markCalled() }, {}, {})
            val first = task { coordinator.performFullSync(radioId, store, delaying, MockChannelService(), MockMessagePollingService()) }
            waitUntil("First sync should have started") { started.callCount >= 1 }
            val second = coordinator.performFullSync(radioId, store, delaying, MockChannelService(), MockMessagePollingService())
            assertEquals(FullSyncResult.SKIPPED, second)
            assertEquals(1, started.callCount, "onSyncActivityStarted should only be called once even with duplicate performFullSync calls")
            delaying.completeSync()
            first.cancel()
        },
        case("Cancellation during channels phase ends sync activity once and resets state") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val delaying = DelayingChannelService()
            val ended = CallTracker()
            val polling = MockMessagePollingService()
            coordinator.setSyncActivityCallbacks({}, { ended.markCalled() }, {})
            val sync = task { coordinator.performFullSync(radioId, store, MockContactService(), delaying, polling) }
            delaying.waitForSyncStart()
            sync.cancel()
            assertFailsWith<CancellationException> { sync.await() }
            assertEquals(1, ended.callCount, "onSyncActivityEnded should be called exactly once on cancellation")
            assertEquals(SyncState.Idle, coordinator.state, "Sync state should reset to idle on cancellation")
            assertEquals(0, polling.pollAllMessagesCallCount)
        },
        case("performFullSync clears notification suppression after poll completes") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val notifications = NotificationHarness(scope, radioId).service
            notifications.isSuppressingNotifications = true
            assertTrue(notifications.isSuppressingNotifications)
            coordinator.performFullSync(
                radioId, store, MockContactService(), MockChannelService(), MockMessagePollingService(),
                notificationService = notifications.syncSuppressionPort(),
            )
            assertFalse(notifications.isSuppressingNotifications)
        },
        case("Contact sync passes lastContactSync timestamp from device") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val watermark = 1_704_067_200u
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = watermark)
            val contacts = MockContactService()
            coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService(), null)
            assertEquals(1, contacts.syncContactsInvocations.size)
            // The device filter is strictly greater-than, so the window is rewound one second.
            assertEquals(Instant.ofEpochSecond(watermark.toLong() - 1), contacts.syncContactsInvocations[0].since)
        },
        case("Successful sync passes succeeded: true to onEnded callback") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val values = ValueTracker<Boolean>()
            coordinator.setSyncActivityCallbacks({}, { values.record(it) }, {})
            coordinator.performFullSync(radioId, store, MockContactService(), MockChannelService(), MockMessagePollingService())
            assertEquals(listOf(true), values.values, "Successful sync should pass succeeded: true")
        },
        case("Failed sync passes succeeded: false to onEnded callback") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId)
            val values = ValueTracker<Boolean>()
            coordinator.setSyncActivityCallbacks({}, { values.record(it) }, {})
            val contacts = MockContactService().apply { stubbedSyncContactsResult = Result.failure(SyncCoordinatorError.SyncFailed("Test error")) }
            assertFailsWith<SyncCoordinatorError.SyncFailed> {
                coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService())
            }
            assertEquals(listOf(false), values.values, "Failed sync should pass succeeded: false")
        },
        case("beginResyncActivity and endResyncActivity fire the correct callbacks") {
            val coordinator = coordinator()
            val started = CallTracker()
            val values = ValueTracker<Boolean>()
            coordinator.setSyncActivityCallbacks({ started.markCalled() }, { values.record(it) }, {})
            coordinator.beginResyncActivity()
            assertEquals(1, started.callCount, "beginResyncActivity should fire onStarted")
            coordinator.endResyncActivity(true)
            assertEquals(listOf(true), values.values)
            coordinator.beginResyncActivity()
            coordinator.endResyncActivity(false)
            assertEquals(listOf(true, false), values.values)
        },
        case("Disconnect during resync does not double-end the resync bracket") {
            val coordinator = coordinator()
            val values = ValueTracker<Boolean>()
            coordinator.setSyncActivityCallbacks({}, { values.record(it) }, {})
            coordinator.beginResyncActivity()
            coordinator.onDisconnected(FakeNotificationService())
            assertTrue(values.values.isEmpty(), "onDisconnected should not end the resync bracket")
        },
    )
}

/** The successful ContactSyncResult helper used throughout (Swift `ContactSyncResult(...)`). */
internal fun contactResult(received: Long, watermark: UInt, incremental: Boolean = true) =
    Result.success(ContactSyncResult(received, watermark, incremental))
