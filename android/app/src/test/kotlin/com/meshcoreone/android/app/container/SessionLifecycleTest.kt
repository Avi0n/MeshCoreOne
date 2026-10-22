// AndroidOnly: WP-303 Native proof: reconnect and radio switch build one graph per generation and release every monitor and stream.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionLifecycleTest : RoomProcessTest() {
    private suspend fun ContainerHarness.connect(target: ConnectionTarget = target()) {
        manager.connect(target)
        settle()
        assertReady()
    }

    private suspend fun ContainerHarness.disconnect() {
        manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        settle()
    }

    @Test
    fun reconnectCyclesKeepOneLiveGraphAndNeverGrowSubscribersOrJobs() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val seen = mutableListOf<RadioSessionContainer>()
            val dispatcherJobs = mutableListOf<Int>()
            val sessionJobs = mutableListOf<Int>()
            val dataSubscribers = mutableListOf<Int>()
            repeat(3) {
                h.connect()
                val live = assertNotNull(h.sessions.current)
                seen += live
                dispatcherJobs += h.appState.messageEventDispatcher.activeJobCount
                sessionJobs += h.appState.activeSessionJobCount
                dataSubscribers += live.syncCoordinator.dataEventBroadcaster.subscriberCount
                assertEquals(1, h.sessions.outstanding, "exactly one live graph while connected")
                h.disconnect()
                assertNull(h.sessions.current)
                assertEquals(0, h.sessions.outstanding, "no graph survives a disconnect")
            }
            assertEquals(3, h.sessions.created)
            assertEquals(3, h.sessions.tornDown)
            assertEquals(3, seen.map { System.identityHashCode(it) }.toSet().size)
            assertEquals(1, dispatcherJobs.toSet().size, "dispatcher collectors do not accumulate: $dispatcherJobs")
            assertEquals(1, sessionJobs.toSet().size, "app-state collectors do not accumulate: $sessionJobs")
            assertEquals(1, dataSubscribers.toSet().size, "data-event subscribers do not accumulate: $dataSubscribers")
            for (old in seen) {
                assertTrue(old.isTornDown)
                assertFalse(old.isEventMonitoringActive)
                assertEquals(0, old.liveJobCount, "no coroutine outlives its graph")
                assertEquals(0, old.syncCoordinator.dataEventBroadcaster.subscriberCount)
            }
            assertTrue(h.links.radios.all { it.closes == 1 && it.readers == 0 }, "each physical link closed exactly once")
        } finally { h.container.close() }
    }

    @Test
    fun radioSwitchTearsDownTheOldGraphBeforeTheNewOneServes() = runTest {
        val h = ContainerHarness(this, store, identity = { target ->
            if (target is ConnectionTarget.Bluetooth && target.deviceId == SECOND) key(9) else key(7)
        })
        try {
            h.connect(h.target(FIRST))
            val first = assertNotNull(h.sessions.current)
            val firstRadio = first.token.radioId
            val before = h.appState.servicesVersion.value
            h.manager.connect(h.target(SECOND))
            h.settle()
            h.assertReady()
            val second = assertNotNull(h.sessions.current)
            assertNotSame(first, second)
            assertNotEquals(firstRadio, second.token.radioId, "the second radio is a different identity")
            assertTrue(first.isTornDown)
            assertEquals(0, first.liveJobCount)
            assertEquals(1, h.sessions.outstanding)
            assertTrue(h.appState.servicesVersion.value > before, "views reload on a services change")
            assertSame(second.syncCoordinator, h.appState.syncCoordinator)
            assertEquals(second.token.radioId, h.appState.currentRadioId)
        } finally { h.container.close() }
    }

    @Test
    fun teardownFinishesEveryEventStreamAndClearsNotificationForwarders() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            var completed = false
            // Subscribed straight on the coordinator and collected outside app state: only the container's own
            // finishDataEvents() can end this stream.
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.syncCoordinator.dataEvents().collect { }
                completed = true
            }
            assertNotNull(live.notificationService.onQuickReply, "app state installed the forwarders")
            h.disconnect()
            h.eventually("the stream to finish") { completed }
            collector.cancel()
            assertNull(live.notificationService.onQuickReply)
            assertNull(live.notificationService.onChannelQuickReply)
            assertNull(live.notificationService.onMarkAsRead)
            assertNull(live.notificationService.onChannelMarkAsRead)
            assertNull(live.notificationService.onRoomMarkAsRead)
        } finally { h.container.close() }
    }

    @Test
    fun startingMonitorsTwiceDoesNotDuplicateAnyMonitor() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertTrue(live.isEventMonitoringActive)
            val before = live.liveJobCount
            live.startEventMonitoring(live.token.radioId)
            live.startEventMonitoring(live.token.radioId, enableAutoFetch = true, enableAdvertisementMonitoring = true)
            h.settle()
            assertEquals(before, live.liveJobCount, "a second start must be a no-op")
        } finally { h.container.close() }
    }

    @Test
    fun tearDownIsIdempotentAndRefusesToRestartMonitors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            h.disconnect()
            val again = live.tearDown()
            assertTrue(again.isComplete, "a repeated teardown replays the first receipt: ${again.issues}")
            live.startEventMonitoring(live.token.radioId)
            assertFalse(live.isEventMonitoringActive, "a torn-down graph never restarts a monitor")
            assertEquals(0, live.liveJobCount)
        } finally { h.container.close() }
    }

    @Test
    fun processGlobalDebugLogBufferNeverPointsAtATornDownGraph() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertSame(live.debugLogBuffer, DebugLogBuffer.shared)
            h.disconnect()
            assertNotSame(live.debugLogBuffer, DebugLogBuffer.shared)
            assertSame(h.container.bootstrapDebugLog, DebugLogBuffer.shared)
        } finally { h.container.close() }
    }

    @Test
    fun explicitDisconnectRunsTheSameSessionTeardownAsConnectionLoss() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            assertTrue(h.appState.activeSessionJobCount > 0)
            h.appState.disconnect()
            h.settle()
            assertEquals(0, h.appState.activeSessionJobCount)
            assertEquals(0, h.appState.messageEventDispatcher.activeJobCount)
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
        } finally { h.container.close() }
    }

    private companion object {
        val FIRST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    }
}
