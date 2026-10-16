package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLogoutLifetimeTest {
    @Test
    fun anAlreadyRetiredLogoutHasNoEffects() = runTest {
        val harness = harness()
        try {
            harness.service.retireConnectionOperations()
            val outcome = runCatching { harness.service.logout(harness.seed.id) }
            runCurrent()

            harness.assertUnchanged()
            assertEquals(0, harness.reads)
            assertTrue(currentCoroutineContext().job.isActive)
            assertTrue(backgroundScope.coroutineContext.job.isActive)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
        } finally {
            harness.close()
        }
    }

    @Test
    fun retirementDuringInitialFetchHasNoEffects() = runTest {
        val harness = harness()
        harness.holdFetch = true
        var callerSurvived = false
        val caller = backgroundScope.async {
            runCatching { harness.service.logout(harness.seed.id) }.also {
                callerSurvived = currentCoroutineContext().job.isActive
            }
        }
        try {
            runCurrent()
            val operation = harness.fetchEntered.await()
            assertTrue(operation.isActive)
            assertTrue(caller.isActive)
            harness.service.retireConnectionOperations()
            harness.releaseFetch.complete(Unit)
            runCurrent()
            val outcome = caller.await()
            caller.join()

            harness.assertUnchanged()
            assertEquals(1, harness.reads)
            assertTrue(callerSurvived)
            assertTrue(backgroundScope.coroutineContext.job.isActive)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
        } finally {
            harness.releaseFetch.complete(Unit)
            caller.cancelAndJoin()
            harness.close()
        }
    }

    @Test
    fun backgroundStopsLeaveLiveLogoutUsable() = runTest {
        val harness = harness()
        try {
            harness.service.stopEventMonitoring()
            harness.service.stopAllKeepAlives()
            harness.service.logout(harness.seed.id)
            runCurrent()

            assertWholeRow(
                harness.seed.copy(isConnected = false, permissionLevel = RoomPermissionLevel.GUEST),
                requireNotNull(harness.backing.fetchSessionById(harness.seed.id)),
            )
            assertEquals(1, harness.reads)
            assertEquals(1, harness.writes)
            assertEquals(1, harness.radio.sendLogoutCalls.size)
            assertContentEquals(harness.seed.publicKey, harness.radio.sendLogoutCalls.single())
            assertEquals(
                listOf<RemoteNodeEvent>(RemoteNodeEvent.SessionStateChanged(harness.seed.id, false)),
                harness.events,
            )
            assertTrue(backgroundScope.coroutineContext.job.isActive)
        } finally {
            harness.close()
        }
    }

    private suspend fun TestScope.harness(): LogoutHarness {
        val seed = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN).copy(
            unreadCount = UNREAD,
            notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isPinned = true,
            lastSyncTimestamp = SYNC_TIME,
            lastConnectedDate = CONNECTED_TIME,
            lastBatteryMillivolts = BATTERY,
            lastMessageDate = MESSAGE_TIME,
        )
        val backing = InMemoryRemoteNodeStore()
        backing.saveSession(seed)
        return LogoutHarness(backgroundScope, backing, seed)
    }

    private class LogoutHarness(
        scope: CoroutineScope,
        val backing: InMemoryRemoteNodeStore,
        val seed: RemoteNodeSessionDto,
    ) {
        val fetchEntered = CompletableDeferred<Job>()
        val releaseFetch = CompletableDeferred<Unit>()
        var holdFetch = false
        var reads = 0
        var writes = 0
        val radio = FakeNodeSession()
        val events = mutableListOf<RemoteNodeEvent>()
        private val store = object : RemoteNodeStore by backing {
            override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? {
                reads += 1
                if (holdFetch) {
                    fetchEntered.complete(currentCoroutineContext().job)
                    releaseFetch.await()
                }
                return backing.fetchSessionById(id)
            }

            override suspend fun updateSessionConnection(
                id: Uuid,
                isConnected: Boolean,
                permissionLevel: RoomPermissionLevel,
            ) {
                writes += 1
                backing.updateSessionConnection(id, isConnected, permissionLevel)
            }
        }
        val service = RemoteNodeService(radio, store, InMemoryNodePasswordStore(), scope)
        private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { events += it }
        }

        suspend fun assertUnchanged() {
            assertWholeRow(seed, requireNotNull(backing.fetchSessionById(seed.id)))
            assertEquals(0, radio.sendLogoutCalls.size)
            assertEquals(0, writes)
            assertTrue(events.isEmpty())
        }

        suspend fun close() {
            releaseFetch.complete(Unit)
            collector.cancelAndJoin()
            service.stopEventMonitoring()
            service.stopAllKeepAlives()
        }
    }

    private companion object {
        const val UNREAD = 7
        const val SYNC_TIME = 123L
        const val CONNECTED_TIME = 1_700_000_000.0
        const val BATTERY = 3800
        const val MESSAGE_TIME = CONNECTED_TIME - 10.0

        fun assertWholeRow(expected: RemoteNodeSessionDto, actual: RemoteNodeSessionDto) {
            assertContentEquals(expected.publicKey, actual.publicKey)
            assertEquals(expected.copy(publicKey = actual.publicKey), actual)
        }
    }
}
