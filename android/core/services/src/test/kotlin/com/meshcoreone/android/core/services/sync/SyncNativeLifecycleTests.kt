// AndroidOnly: WP-214 Native lifecycle, claim-ownership, watchdog and retry-timing cases for the Kotlin adaptation.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import java.time.Duration as JavaDuration
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Shared ordered log of lifecycle steps across collaborators. */
private class StepLog {
    val steps: MutableList<String> = Collections.synchronizedList(ArrayList())
    fun index(step: String): Int = synchronized(steps) { steps.indexOf(step) }.also { check(it >= 0) { "missing step $step in $steps" } }
}

private class LoggingPolling(private val log: StepLog) : MockMessagePollingService() {
    override suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit) {
        log.steps += "wire.contact"
        super.setContactMessageHandler(handler)
    }
    override suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit) {
        log.steps += "wire.channel"
        super.setChannelMessageHandler(handler)
    }
    override suspend fun pollAllMessages(): Long {
        log.steps += "poll"
        return super.pollAllMessages()
    }
    override suspend fun waitForPendingHandlers(timeout: JavaDuration): Boolean {
        log.steps += "drain"
        return super.waitForPendingHandlers(timeout)
    }
    override suspend fun startAutoFetch(radioId: RadioId) {
        log.steps += "autoFetch"
        super.startAutoFetch(radioId)
    }
}

/** Channel service whose retry answers come from a queue and whose call times are recorded. */
private class ScriptedChannelService(private val clock: SyncClock, vararg retries: ChannelSyncResult) : ChannelServiceProtocol {
    private val queue = ArrayDeque(retries.toList())
    val retryTimes: MutableList<Duration> = Collections.synchronizedList(ArrayList())
    val retryIndices: MutableList<List<UByte>> = Collections.synchronizedList(ArrayList())
    @Volatile var syncResult = ChannelSyncResult(0)
    override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean) = syncResult
    override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult {
        retryTimes += clock.elapsed()
        retryIndices += indices.toList()
        return synchronized(queue) { queue.removeFirstOrNull() } ?: ChannelSyncResult(0)
    }
}

private fun timeouts(vararg indices: Int) =
    indices.map { ChannelSyncError(it.toUByte(), ChannelSyncErrorType.Timeout, "timeout") }.snapshot()

class SyncNativeLifecycleTests {
    @TestFactory
    fun channelCooldownBoundaries(): List<DynamicTest> = listOf(false, true).map { attempted ->
        nativeCase("${if (attempted) "attempted" else "clean"} channel cooldown expires exactly at 30 seconds only for ESP32") {
            for (platform in DevicePlatform.entries) {
                for (transport in TransportType.entries) {
                    for (ageMillis in listOf(29_999L, 30_000L)) {
                        val radioId = newRadio()
                        val (controller, host) = retryController()
                        host.detectedPlatform = platform
                        val previous = radioId to clock.now().minusMillis(ageMillis)
                        if (attempted) host.lastAttemptedChannelSync = previous else host.lastCleanChannelSync = previous
                        val channels = MockChannelService()
                        val polling = MockMessagePollingService()
                        val coordinator = coordinator()
                        val result = coordinator.performFullSync(
                            radioId, SyncInMemoryStore.createTestDataStore(radioId),
                            MockContactService(), channels, polling,
                            channelSyncConfig = controller.currentChannelSyncConfig(radioId, transport),
                        )
                        val skip = platform == DevicePlatform.ESP32 && ageMillis < 30_000L
                        val context = "$platform / $transport / $ageMillis ms / attempted=$attempted"
                        assertEquals(if (skip) 0 else 1, channels.syncChannelsInvocations.size, context)
                        assertEquals(if (skip) SyncPhaseStatus.Skipped else SyncPhaseStatus.Clean, result.channels, context)
                        assertEquals(1, polling.pollAllMessagesCallCount, context)
                        assertTrue(result.isConnectionUsable, context)
                        assertEquals(SyncState.Synced, coordinator.state, context)
                    }
                }
            }
        }
    }

    @TestFactory
    fun lifecycle(): List<DynamicTest> = listOf(
        nativeCase("connection setup wires handlers before monitoring and starts auto-fetch only after notifications resume") {
            val radioId = newRadio()
            val log = StepLog()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId), polling = LoggingPolling(log))
            val base = services.dependencies()
            val notifications = object : SyncNotificationServicing by services.notifications {
                override suspend fun setSuppressingNotifications(suppressing: Boolean) {
                    log.steps += "suppress=$suppressing"
                    services.notifications.setSuppressingNotifications(suppressing)
                }
            }
            val adverts = object : SyncAdvertisementServicing by services.adverts {
                override suspend fun setSyncingContacts(isSyncing: Boolean) {
                    log.steps += "advertSyncing=$isSyncing"
                }
                override fun events() = services.adverts.events().also { log.steps += "discovery" }
            }
            val deps = base.copy(
                notificationService = notifications, advertisementService = adverts,
                startEventMonitoring = { _, autoFetch -> log.steps += "monitor(autoFetch=$autoFetch)" },
            )
            val result = coordinator().onConnectionEstablished(radioId, deps)
            assertTrue(result.isConnectionUsable)
            val order = listOf(
                "suppress=true", "advertSyncing=true", "wire.contact", "wire.channel", "monitor(autoFetch=false)", "poll",
                "discovery", "advertSyncing=false", "drain", "autoFetch",
            ).map(log::index)
            assertEquals(order.sorted(), order, "steps out of order: ${log.steps}")
            assertTrue(log.index("suppress=false") < log.index("autoFetch"), "notifications resume before auto-fetch")
            assertEquals(log.steps.lastIndex, log.index("autoFetch"), "auto-fetch is the last step")
            assertNotNull(services.adverts.deltaSyncHandler, "advert delta handler wired before sync")
            assertEquals(64, services.rxLog.privateKey?.size, "private key exported to the RX log")
        },
        nativeCase("failed connection setup still drains, resumes notifications and releases advert deferral") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            services.contactService.stubbedSyncContactsResult = Result.failure(SyncCoordinatorError.NotConnected())
            assertFailsWith<SyncCoordinatorError.NotConnected> { coordinator().onConnectionEstablished(radioId, services.dependencies()) }
            assertEquals(listOf(true, false), services.adverts.syncingToggles)
            assertFalse(services.notifications.isSuppressing)
            assertEquals(1, services.polling.waitForPendingHandlersInvocations)
            assertTrue(services.polling.startAutoFetchRadioIds.isEmpty(), "auto-fetch never starts after a failed setup")
            assertNotNull(services.polling.capturedContactMessageHandler, "handlers stay wired so resync can use them")
        },
        nativeCase("racing connection setups wire handlers once and the loser reports skipped") {
            val radioId = newRadio()
            val delaying = DelayingContactService()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val deps = services.dependencies().copy(contactService = delaying)
            val coordinator = coordinator()
            val first = task { coordinator.onConnectionEstablished(radioId, deps) }
            delaying.waitForSyncStart()
            assertEquals(FullSyncResult.SKIPPED, coordinator.onConnectionEstablished(radioId, deps))
            delaying.completeSync()
            assertTrue(first.await().isConnectionUsable)
            assertEquals(1, services.polling.startAutoFetchRadioIds.size)
        },
        nativeCase("suppression watchdog force-clears suppression only after 120 s of virtual time") {
            val coordinator = coordinator()
            val notifications = FakeNotificationService().apply { isSuppressing = true }
            coordinator.startSuppressionWatchdog(notifications)
            sleep(119.seconds)
            assertTrue(notifications.isSuppressing)
            sleep(2.seconds)
            assertFalse(notifications.isSuppressing, "watchdog clears stuck suppression")
        },
        nativeCase("completing a sync cancels the suppression watchdog") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val coordinator = coordinator()
            coordinator.onConnectionEstablished(radioId, services.dependencies())
            assertFalse(coordinator.hasSuppressionWatchdog)
            services.notifications.isSuppressing = true
            sleep(200.seconds)
            assertTrue(services.notifications.isSuppressing, "a cancelled watchdog must not fire later")
        },
        nativeCase("a claim released after a disconnect cannot clear a newer sync's claim") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val coordinator = coordinator()
            val stale = DelayingContactService()
            val fresh = DelayingContactService()
            val first = task { coordinator.performFullSync(radioId, store, stale, MockChannelService(), MockMessagePollingService()) }
            stale.waitForSyncStart()
            coordinator.onDisconnected(FakeNotificationService())
            val second = task { coordinator.performFullSync(radioId, store, fresh, MockChannelService(), MockMessagePollingService()) }
            fresh.waitForSyncStart()
            stale.completeSync()
            first.await()
            assertTrue(coordinator.isSyncInProgress, "the reconnect's claim must survive the stale release")
            assertEquals(SyncAdvertContactSyncOutcome.BUSY, coordinator.performAdvertContactSync(false, radioId, store, MockContactService()))
            fresh.completeSync()
            second.await()
            assertFalse(coordinator.isSyncInProgress)
        },
        nativeCase("full sync waits out an advert claim instead of skipping") {
            val radioId = newRadio()
            val store = SyncInMemoryStore.createTestDataStore(radioId, lastContactSync = 1_704_067_200u)
            val coordinator = coordinator()
            val gated = GatedContactService()
            val advert = task { coordinator.performAdvertContactSync(false, radioId, store, gated) }
            gated.waitForSyncStart()
            val contacts = MockContactService()
            val full = task { coordinator.performFullSync(radioId, store, contacts, MockChannelService(), MockMessagePollingService()) }
            sleep(1.seconds)
            assertFalse(full.isCompleted, "full sync must wait while the advert claim is held")
            assertEquals(1, coordinator.advertSyncWaiterCount)
            gated.release()
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
            assertTrue(full.await().isConnectionUsable, "the full sync runs for real once released")
            assertEquals(1, contacts.syncContactsInvocations.size)
        },
        nativeCase("cancelled resync rethrows cancellation and still resumes auto-fetch and notifications") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val delaying = DelayingContactService()
            val coordinator = coordinator()
            val resync = task { coordinator.performResync(radioId, services.dependencies().copy(contactService = delaying)) }
            delaying.waitForSyncStart()
            assertTrue(services.notifications.isSuppressing)
            resync.cancel()
            assertFailsWith<CancellationException> { resync.await() }
            assertEquals(1, services.polling.pauseAutoFetchCallCount)
            assertEquals(1, services.polling.resumeAutoFetchCallCount)
            assertFalse(services.notifications.isSuppressing)
            assertFalse(coordinator.isSyncInProgress)
            assertEquals(SyncState.Idle, coordinator.state)
        },
        nativeCase("successful resync restarts discovery monitoring and resumes auto-fetch") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val coordinator = coordinator()
            assertTrue(coordinator.performResync(radioId, services.dependencies()))
            assertTrue(coordinator.isDiscoveryMonitoring)
            assertEquals(1, services.adverts.subscriberCount)
            assertEquals(1, services.polling.resumeAutoFetchCallCount)
            assertFalse(services.notifications.isSuppressing)
        },
        nativeCase("resync that collides with a held sync claim reports not usable and still resumes auto-fetch") {
            val radioId = newRadio()
            val services = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val delaying = DelayingContactService()
            val coordinator = coordinator()
            val holder = task { coordinator.performFullSync(radioId, services.dataStore, delaying, MockChannelService(), MockMessagePollingService()) }
            delaying.waitForSyncStart()
            assertFalse(coordinator.performResync(radioId, services.dependencies()), "a skipped full sync has no usable contacts")
            assertEquals(1, services.polling.resumeAutoFetchCallCount)
            assertTrue(services.contactService.syncContactsInvocations.isEmpty())
            delaying.completeSync()
            holder.await()
        },
    )

    @TestFactory
    fun cancellationRobustness(): List<DynamicTest> = listOf(
        nativeCase("a collaborator's own CancellationException fails the sync instead of posing as cancellation") {
            val h = makeResyncHarness()
            val contacts = h.services.dependencies.contactService as MockContactService
            contacts.stubbedSyncContactsResult = Result.failure(CancellationException("contact stream timeout"))
            assertFalse(h.controller.performInitialSync(h.radioId, h.services))
            assertTrue(h.services.syncCoordinator.state is SyncState.Failed, "state ${h.services.syncCoordinator.state}")
            assertNotNull(h.controller.resyncTask, "a timeout is a failed sync, so the resync loop starts")
            h.controller.cancelResyncLoop()
        },
        nativeCase("cancelling a channel-only retry inside the activity callback still closes the bracket") {
            val coordinator = coordinator()
            val gate = CompletableDeferred<Unit>()
            val starts = CallTracker()
            val ends = ValueTracker<Boolean>()
            coordinator.setSyncActivityCallbacks({ starts.markCalled(); gate.await() }, { ends.record(it) }, {})
            val retry = task { coordinator.retryChannels(newRadio(), MockChannelService(), listOf(1u)) }
            waitUntil("activity started") { starts.wasCalled }
            retry.cancel()
            assertFailsWith<CancellationException> { retry.await() }
            assertEquals(listOf(false), ends.values)
            assertEquals(SyncState.Idle, coordinator.state)
            assertFalse(coordinator.isSyncInProgress)
        },
        nativeCase("cancelling the resync loop while its bracket is opening still balances the bracket") {
            val h = makeResyncHarness()
            val gate = CompletableDeferred<Unit>()
            val starts = CallTracker()
            val ends = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({ starts.markCalled(); gate.await() }, { ends.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("bracket opening") { starts.wasCalled }
            h.controller.cancelResyncLoop()
            gate.complete(Unit)
            waitUntil("bracket closed") { ends.values.isNotEmpty() }
            settle()
            assertEquals(1, starts.callCount)
            assertEquals(listOf(false), ends.values)
        },
        nativeCase("onDisconnected completes its safety net even when the caller is cancelled") {
            val coordinator = coordinator()
            val gate = CompletableDeferred<Unit>()
            val notifications = object : SyncNotificationServicing by FakeNotificationService() {
                @Volatile var suppressing = true
                override suspend fun setSuppressingNotifications(suppressing: Boolean) {
                    gate.await()
                    this.suppressing = suppressing
                }
            }
            coordinator.setState(SyncState.Syncing(SyncProgress(SyncPhase.MESSAGES, 0, 0)))
            val disconnect = task { coordinator.onDisconnected(notifications) }
            settle()
            disconnect.cancel()
            gate.complete(Unit)
            runCatchingCancellation { disconnect.await() }
            assertFalse(notifications.suppressing)
            assertEquals(SyncState.Idle, coordinator.state)
        },
    )

    @TestFactory
    fun retryTiming(): List<DynamicTest> = listOf(
        nativeCase("channel-only retry backs off 2 s then 4 s and stops once channels recover") {
            val (controller, host) = retryController()
            val radioId = newRadio()
            val fakes = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val channels = ScriptedChannelService(clock, ChannelSyncResult(0, timeouts(5)), ChannelSyncResult(1))
            val services = SyncRetryServices(coordinator(), fakes.dependencies().copy(channelService = channels), fakes.remoteNode)
            host.connectionState = DeviceConnectionState.READY
            host.currentServices = services
            val start = clock.elapsed()
            controller.scheduleChannelOnlyRetry(radioId, services, listOf(5u, 3u, 5u))
            assertTrue(controller.shouldPauseWiFiHeartbeatProbe, "a pending channel retry pauses the WiFi heartbeat")
            waitUntil("retry finishes", 30.seconds) { controller.channelRetryTask == null }
            assertEquals(listOf(2.seconds, 6.seconds), channels.retryTimes.map { it - start })
            assertEquals(listOf(listOf<UByte>(3u, 5u), listOf<UByte>(5u)), channels.retryIndices.toList())
            assertFalse(controller.shouldPauseWiFiHeartbeatProbe)
        },
        nativeCase("channel-only retry stops at non-retryable errors and exhausts after two attempts") {
            val (controller, host) = retryController()
            val radioId = newRadio()
            val fakes = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val nonRetryable = ChannelSyncResult(0, SnapshotList.of(ChannelSyncError(4u, ChannelSyncErrorType.DeviceError(2u), "bad")))
            val stopping = ScriptedChannelService(clock, nonRetryable)
            val exhausting = ScriptedChannelService(clock, ChannelSyncResult(0, timeouts(4)), ChannelSyncResult(0, timeouts(4)), ChannelSyncResult(1))
            host.connectionState = DeviceConnectionState.READY
            for (channels in listOf(stopping, exhausting)) {
                val services = SyncRetryServices(coordinator(), fakes.dependencies().copy(channelService = channels), fakes.remoteNode)
                host.currentServices = services
                controller.scheduleChannelOnlyRetry(radioId, services, listOf(4u))
                waitUntil("retry finishes", 30.seconds) { controller.channelRetryTask == null }
            }
            assertEquals(1, stopping.retryTimes.size)
            assertEquals(2, exhausting.retryTimes.size, "bounded at two attempts")
        },
        nativeCase("channel-only retry fences on the live service graph") {
            val (controller, host) = retryController()
            val radioId = newRadio()
            val fakes = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val channels = ScriptedChannelService(clock)
            val services = SyncRetryServices(coordinator(), fakes.dependencies().copy(channelService = channels), fakes.remoteNode)
            host.connectionState = DeviceConnectionState.READY
            host.currentServices = null
            controller.scheduleChannelOnlyRetry(radioId, services, listOf(1u))
            waitUntil("retry finishes", 30.seconds) { controller.channelRetryTask == null }
            assertTrue(channels.retryTimes.isEmpty())
        },
        nativeCase("initial sync with a partial channel phase schedules a channel-only retry") {
            val (controller, host) = retryController()
            val radioId = newRadio()
            val fakes = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId))
            val channels = ScriptedChannelService(clock, ChannelSyncResult(0, timeouts(2)), ChannelSyncResult(1)).apply {
                syncResult = ChannelSyncResult(7, timeouts(2))
            }
            val services = SyncRetryServices(coordinator(), fakes.dependencies().copy(channelService = channels), fakes.remoteNode)
            host.connectionState = DeviceConnectionState.SYNCING
            host.currentServices = services
            assertTrue(controller.performInitialSync(radioId, services))
            assertNotNull(controller.channelRetryTask)
            assertNull(controller.resyncTask)
            waitUntil("channel retry runs", 30.seconds) { controller.channelRetryTask == null }
            assertEquals(2, channels.retryTimes.size, "one in-phase retry plus one channel-only retry")
        },
        nativeCase("initial sync failure starts the resync loop unless the user disconnected") {
            val h = makeResyncHarness()
            assertFalse(h.controller.performInitialSync(h.radioId, h.services))
            assertNotNull(h.controller.resyncTask)
            h.controller.cancelResyncLoop()
            h.host.connectionIntent = com.meshcoreone.android.core.contracts.domain.ConnectionIntent.UserDisconnected
            assertFalse(h.controller.performInitialSync(h.radioId, h.services))
            assertNull(h.controller.resyncTask)
        },
        nativeCase("restarting the resync loop cancels the old loop without letting it clear the new one") {
            val h = makeResyncHarness()
            val gate = CompletableDeferred<Boolean>()
            h.services.syncCoordinator.setPerformResyncOverride { _, _ -> gate.await() }
            h.controller.startResyncLoop(h.radioId, h.services)
            val first = checkNotNull(h.controller.resyncTask)
            sleep(3.seconds)
            h.controller.startResyncLoop(h.radioId, h.services)
            val second = checkNotNull(h.controller.resyncTask)
            assertNotSame(first, second)
            first.join()
            assertEquals(second, h.controller.resyncTask, "the cancelled loop must not clear its replacement")
            h.controller.cancelResyncLoop()
        },
        nativeCase("resync success re-authenticates waiting room sessions before onDeviceSynced") {
            val h = makeResyncHarness()
            val session = java.util.UUID.randomUUID()
            h.host.awaitingReauth += session
            h.services.syncCoordinator.setPerformResyncOverride { _, _ -> true }
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("onDeviceSynced", 10.seconds) { h.host.deviceSynced.wasCalled }
            val remote = h.services.remoteNodeService as FakeRemoteNodeService
            assertEquals(listOf(setOf(com.meshcoreone.android.core.contracts.domain.EntityKey(h.radioId, session))), remote.reauthenticated.toList())
            assertTrue(h.host.awaitingReauth.isEmpty())
            assertTrue(h.host.timeSyncs.wasCalled)
            assertNull(h.controller.resyncTask)
        },
    )
}

/** Awaits a job that may have been cancelled, ignoring only its cancellation. */
private suspend fun runCatchingCancellation(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: CancellationException) {
        // expected
    }
}
