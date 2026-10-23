// AndroidOnly: WP-303 Native proof: process start, connection loss, foreground reconciliation and idempotent wiring over the real graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.ui.StatusPillState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

class ProcessContainerTest : RoomProcessTest() {
    private suspend fun ContainerHarness.connect(id: UUID = UUID.randomUUID()): UUID {
        manager.connect(target(id))
        settle()
        assertReady()
        return id
    }

    @Test
    fun processStartRestoresTheLastConnectionThroughTheRealGraph() = runTest {
        val id = UUID.randomUUID()
        val radio = RadioId(UUID.randomUUID())
        store.saveDevice(DeviceDTO(id = id, radioId = radio, publicKey = key(7), nodeName = "Restored", isActive = true))
        val h = ContainerHarness(this, store)
        try {
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_DEVICE_ID] = RuntimePreferenceValue.Text(id.canonicalString())
            h.preferences.values[PersistenceKeys.LAST_CONNECTED_RADIO_ID] = RuntimePreferenceValue.Text(radio.canonicalString)
            assertNull(h.sessions.current, "no service graph exists before the process connects")
            h.container.start().join()
            h.settle()
            h.assertReady()
            val live = assertNotNull(h.sessions.current)
            assertEquals(radio, live.token.radioId)
            assertEquals(id, h.container.connectionPort.lastConnectedDeviceId)
            assertNotNull(h.appState.offlineDataStore)
        } finally { h.container.close() }
    }

    @Test
    fun connectionLossTearsTheGraphDownAndTheReconnectBuildsExactlyOneNew() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val first = assertNotNull(h.sessions.current)
            h.links.links.last().callbacks!!.onDisconnected(IllegalStateException("link lost"))
            h.eventually("the lost graph to be torn down", virtualBudgetMillis = 0) { h.sessions.outstanding == 0 }
            assertTrue(first.isTornDown)
            assertEquals(0, first.liveJobCount)
            assertNull(h.sessions.current)
            assertTrue(h.appState.statusPillState != StatusPillState.Ready)
            h.eventually("the watchdog reconnect", virtualBudgetMillis = 120_000, virtualStepMillis = 200) {
                h.manager.connectionState == DeviceConnectionState.READY
            }
            assertEquals(1, h.sessions.outstanding, "exactly one live graph after the reconnect")
            assertTrue(h.sessions.created >= 2)
            assertTrue(assertNotNull(h.sessions.current) !== first)
        } finally { h.container.close() }
    }

    @Test
    fun rewiringTheSameGraphNeitherBumpsVersionsNorAccumulatesCollectors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val version = h.appState.servicesVersion.value
            val sessionJobs = h.appState.activeSessionJobCount
            val dispatcherJobs = h.appState.messageEventDispatcher.activeJobCount
            repeat(3) { h.appState.wireServicesIfConnected() }
            h.settle()
            assertEquals(version, h.appState.servicesVersion.value, "a rewire of the same container is not a services change")
            assertEquals(sessionJobs, h.appState.activeSessionJobCount)
            assertEquals(dispatcherJobs, h.appState.messageEventDispatcher.activeJobCount)
        } finally { h.container.close() }
    }

    @Test
    fun readyToastFollowsASuccessfulSyncAndThePillNeverClaimsReadyEarlier() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val seen = mutableListOf<Pair<DeviceConnectionState, StatusPillState>>()
            val watcher = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                h.manager.snapshot.collect { seen += h.manager.connectionState to h.appState.statusPillState }
            }
            h.connect()
            watcher.cancel()
            assertTrue(seen.none { (state, pill) -> pill == StatusPillState.Ready && state != DeviceConnectionState.READY },
                "Ready is never shown while the connection is not READY: $seen")
            assertTrue(h.appState.connectionUI.showReadyToast, "a clean sync ends with the ready toast")
            assertEquals(StatusPillState.Ready, h.appState.statusPillState)
            h.eventually("the toast to expire", virtualBudgetMillis = 3_000, virtualStepMillis = 100) { !h.appState.connectionUI.showReadyToast }
            assertEquals(StatusPillState.Hidden, h.appState.statusPillState)
        } finally { h.container.close() }
    }

    @Test
    fun foregroundReconciliationRunsInOrderOverTheRealRuntime() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            assertTrue(h.appState.batteryMonitor.isRefreshLoopActive || h.appState.batteryMonitor.isBootstrapActive)
            h.appState.handleEnterBackground()
            h.settle()
            assertEquals(listOf(false), h.foregroundCalls.filter { !it }.distinct(), "the platform learned about the background")
            assertFalse(h.appState.batteryMonitor.isRefreshLoopActive, "polling stops in the background")
            assertFalse(h.appState.batteryMonitor.isBootstrapActive)

            h.appState.handleReturnToForeground()
            h.settle()
            assertEquals(true, h.foregroundCalls.last(), "the platform learned about the foreground last")
            assertTrue(h.foregroundCalls.indexOf(false) < h.foregroundCalls.lastIndexOf(true))
            assertTrue(h.appState.batteryMonitor.isRefreshLoopActive, "polling resumes on return")
            assertEquals(DeviceConnectionState.READY, h.manager.connectionState)
        } finally { h.container.close() }
    }

    @Test
    fun aBackgroundedAuthFailureDoesNotLatchAnAlert() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.container.foreground.activityStarted()
            h.container.foreground.activityStopped()
            assertFalse(h.container.foreground.isForeground)
            h.appState.handleAuthenticationFailure(UUID.randomUUID(), isAppActive = h.container.foreground.isForeground)
            assertFalse(h.appState.connectionUI.showingConnectionFailedAlert)
            h.container.foreground.activityStarted()
            h.appState.handleAuthenticationFailure(UUID.randomUUID(), isAppActive = h.container.foreground.isForeground)
            assertTrue(h.appState.connectionUI.showingConnectionFailedAlert)
        } finally { h.container.close() }
    }

    @Test
    fun closingTheProcessTearsDownTheLiveGraphAndLeavesNothingRunning() = runTest {
        val h = ContainerHarness(this, store)
        h.connect()
        val live = assertNotNull(h.sessions.current)
        h.container.close()
        assertTrue(live.isTornDown)
        assertEquals(0, h.sessions.outstanding)
        assertEquals(0, live.liveJobCount)
        assertTrue(h.links.radios.all { it.closes == 1 && it.readers == 0 })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun roomOpenKeepsMainResponsiveAndClosesDatabaseWhenCancellationPreventsOwnershipTransfer() = runTest {
        val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "wp303-room-open-main") }
            .asCoroutineDispatcher()
        val processJob = SupervisorJob()
        val openStarted = CountDownLatch(1)
        val openRelease = CountDownLatch(1)
        val openThread = AtomicReference<Thread?>()
        val transferThread = AtomicReference<Thread?>()
        val openedDatabase = AtomicReference<MeshCoreDatabase?>()
        val cancelledOpenStarted = CountDownLatch(1)
        val cancelledOpenRelease = CountDownLatch(1)
        val cancelledDatabase = AtomicReference<MeshCoreDatabase?>()
        val failedTransferDatabase = AtomicReference<MeshCoreDatabase?>()
        val cancellationTransferCalled = AtomicBoolean()
        var openTask: Deferred<Thread>? = null
        var cancelledOpen: Deferred<MeshCoreDatabase>? = null
        try {
            Dispatchers.setMain(mainDispatcher)
            val processScope = CoroutineScope(processJob + Dispatchers.Main.immediate)
            val mainThread = withContext(Dispatchers.Main.immediate) { Thread.currentThread() }
            val opening = processScope.async {
                val database = openRoomDatabaseOnIo(
                    openDatabase = {
                        openThread.set(Thread.currentThread())
                        openMemoryDatabase().also {
                            it.openHelper.writableDatabase
                            openedDatabase.set(it)
                            openStarted.countDown()
                            check(openRelease.await(30, TimeUnit.SECONDS))
                        }
                    },
                    transferOwnership = {
                        transferThread.set(Thread.currentThread())
                        it
                    },
                    closeUntransferredOwner = {},
                )
                assertTrue(database.isOpen, "successful transfer leaves the process-owned database open")
                val resumedThread = Thread.currentThread()
                withContext(Dispatchers.IO) { database.close() }
                resumedThread
            }
            openTask = opening
            assertTrue(withContext(Dispatchers.IO) { openStarted.await(5, TimeUnit.SECONDS) },
                "Room initialization reaches the blocking opener")
            val heartbeat = CountDownLatch(1)
            processScope.launch { heartbeat.countDown() }
            assertTrue(withContext(Dispatchers.IO) { heartbeat.await(1, TimeUnit.SECONDS) },
                "the main dispatcher remains responsive while Room initialization is blocked")
            openRelease.countDown()
            assertSame(mainThread, opening.await(), "factory continuation returns to its caller dispatcher")
            assertNotSame(mainThread, openThread.get(), "Room initialization runs on Dispatchers.IO")

            val cancellationTask = processScope.async {
                openRoomDatabaseOnIo(
                    openDatabase = {
                        openMemoryDatabase().also {
                            it.openHelper.writableDatabase
                            cancelledDatabase.set(it)
                            cancelledOpenStarted.countDown()
                            check(cancelledOpenRelease.await(30, TimeUnit.SECONDS))
                        }
                    },
                    transferOwnership = {
                        cancellationTransferCalled.set(true)
                        it
                    },
                    closeUntransferredOwner = {},
                )
            }
            cancelledOpen = cancellationTask
            assertTrue(withContext(Dispatchers.IO) { cancelledOpenStarted.await(5, TimeUnit.SECONDS) },
                "cancellable initialization reaches the database ownership boundary")
            val untransferred = assertNotNull(cancelledDatabase.get())
            assertTrue(untransferred.isOpen, "the cancellation case owns an open Room database before cancellation")
            cancellationTask.cancel()
            cancelledOpenRelease.countDown()
            assertFailsWith<CancellationException> { cancellationTask.await() }
            assertFalse(untransferred.isOpen,
                "a database opened before cancellation is closed if ownership was not transferred")
            assertFalse(cancellationTransferCalled.get(), "a cancelled open never reaches its owner constructor")

            val constructionFailure = IllegalStateException("store construction failed")
            val observedFailure = try {
                openRoomDatabaseOnIo<Unit>(
                    openDatabase = {
                        openMemoryDatabase().also {
                            it.openHelper.writableDatabase
                            failedTransferDatabase.set(it)
                        }
                    },
                    transferOwnership = { throw constructionFailure },
                    closeUntransferredOwner = {},
                )
                null
            } catch (failure: IllegalStateException) {
                failure
            }
            assertCoroutineFailureIdentity(constructionFailure, assertNotNull(observedFailure))
            assertFalse(assertNotNull(failedTransferDatabase.get()).isOpen,
                "a database is closed if constructing its process owner fails")
            assertNotSame(mainThread, transferThread.get(), "the process owner is constructed off the main caller")
        } finally {
            openRelease.countDown()
            cancelledOpenRelease.countDown()
            cancelledOpen?.cancelAndJoin()
            openTask?.cancelAndJoin()
            processJob.cancelAndJoin()
            listOfNotNull(openedDatabase.get(), cancelledDatabase.get(), failedTransferDatabase.get())
                .distinct().forEach { database ->
                if (database.isOpen) withContext(Dispatchers.IO) { database.close() }
            }
            Dispatchers.resetMain()
            mainDispatcher.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun roomOwnerConstructionKeepsMainResponsiveAndRetainsProcessLifetime() = runTest {
        val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "wp303-room-owner-main") }
            .asCoroutineDispatcher()
        val processJob = SupervisorJob()
        val storeJob = SupervisorJob(processJob)
        val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
        val constructed = CountDownLatch(1)
        val constructionRelease = CountDownLatch(1)
        val openedDatabase = AtomicReference<MeshCoreDatabase?>()
        val constructedStore = AtomicReference<RoomPersistenceStore?>()
        val ownerThread = AtomicReference<Thread?>()
        val cleanupCalls = AtomicInteger()
        var opening: Deferred<RoomPersistenceStore>? = null
        var primaryFailure: Throwable? = null
        try {
            Dispatchers.setMain(mainDispatcher)
            val mainThread = withContext(Dispatchers.Main.immediate) { Thread.currentThread() }
            val task = CoroutineScope(processJob + Dispatchers.Main.immediate).async {
                val owner = openRoomDatabaseOnIo(
                    openDatabase = {
                        openMemoryDatabase().also {
                            it.openHelper.writableDatabase
                            openedDatabase.set(it)
                        }
                    },
                    transferOwnership = {
                        ownerThread.set(Thread.currentThread())
                        RoomPersistenceStore(it, storeScope).also { owner ->
                            constructedStore.set(owner)
                            constructed.countDown()
                            check(constructionRelease.await(30, TimeUnit.SECONDS))
                        }
                    },
                    closeUntransferredOwner = {
                        cleanupCalls.incrementAndGet()
                        it.close()
                    },
                )
                assertSame(mainThread, Thread.currentThread(), "only publication returns to Main")
                owner
            }
            opening = task
            assertTrue(withContext(Dispatchers.IO) { constructed.await(5, TimeUnit.SECONDS) },
                "the boundary constructs the production RoomPersistenceStore, not just its database")
            val heartbeat = CountDownLatch(1)
            CoroutineScope(processJob + Dispatchers.Main.immediate).launch { heartbeat.countDown() }
            assertTrue(withContext(Dispatchers.IO) { heartbeat.await(1, TimeUnit.SECONDS) },
                "Main remains responsive throughout owner construction")
            assertNotSame(mainThread, ownerThread.get(), "the real repository constructor runs on IO")
            constructionRelease.countDown()
            val owner = task.await()
            assertSame(constructedStore.get(), owner)
            assertTrue(storeJob.isActive)
            assertTrue(storeJob.children.any { it.isActive },
                "repository lifetime belongs to the process, not the completed IO initialization")
            owner.warmUp()
            assertTrue(assertNotNull(openedDatabase.get()).isOpen)
            assertEquals(0, cleanupCalls.get(), "successful publication does not dispose its process owner")
            owner.close()
            assertTrue(storeJob.children.none { it.isActive }, "closing the store releases its repository owner")
            assertTrue(assertNotNull(openedDatabase.get()).isOpen,
                "normal process-store close still does not close the process-owned database")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            constructionRelease.countDown()
            cleanUpRoomOwnerTest(
                primaryFailure,
                { opening?.cancelAndJoin() },
                { withContext(Dispatchers.IO) { constructedStore.get()?.close() } },
                { withContext(Dispatchers.IO) { openedDatabase.get()?.let { if (it.isOpen) it.close() } } },
                { processJob.cancelAndJoin() },
                { Dispatchers.resetMain(); mainDispatcher.close() },
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationAfterRoomOwnerConstructionDisposesItOnceAndPreservesCleanupFailures() = runTest {
        val mainDispatcher = Executors.newSingleThreadExecutor { Thread(it, "wp303-room-cancel-main") }
            .asCoroutineDispatcher()
        var primaryFailure: Throwable? = null
        try {
            Dispatchers.setMain(mainDispatcher)
            val mainThread = withContext(Dispatchers.Main.immediate) { Thread.currentThread() }
            for (failCleanup in listOf(false, true)) {
                val processJob = SupervisorJob()
                val storeJob = SupervisorJob(processJob)
                val storeScope = CoroutineScope(storeJob + Dispatchers.IO)
                val constructed = CountDownLatch(1)
                val constructionRelease = CountDownLatch(1)
                val openedDatabase = AtomicReference<MeshCoreDatabase?>()
                val constructedStore = AtomicReference<RoomPersistenceStore?>()
                val cleanupCalls = AtomicInteger()
                val cleanupFailure = IllegalStateException("owner cleanup failed")
                val cancellation = CancellationException("cancel after the real owner was constructed")
                val propagatedFailure = AtomicReference<CancellationException?>()
                var opening: Deferred<RoomPersistenceStore>? = null
                var caseFailure: Throwable? = null
                try {
                    val task = CoroutineScope(processJob + Dispatchers.Main.immediate).async {
                        try {
                            openRoomDatabaseOnIo(
                                openDatabase = {
                                    openMemoryDatabase().also {
                                        it.openHelper.writableDatabase
                                        openedDatabase.set(it)
                                    }
                                },
                                transferOwnership = {
                                    assertNotSame(mainThread, Thread.currentThread())
                                    RoomPersistenceStore(it, storeScope).also { owner ->
                                        constructedStore.set(owner)
                                        constructed.countDown()
                                        check(constructionRelease.await(30, TimeUnit.SECONDS))
                                    }
                                },
                                closeUntransferredOwner = {
                                    assertNotSame(mainThread, Thread.currentThread(), "cleanup also runs on IO")
                                    assertEquals(1, cleanupCalls.incrementAndGet())
                                    assertTrue(assertNotNull(openedDatabase.get()).isOpen,
                                        "the untransferred store closes before its database")
                                    it.close()
                                    if (failCleanup) throw cleanupFailure
                                },
                            )
                        } catch (failure: CancellationException) {
                            propagatedFailure.set(failure)
                            throw failure
                        }
                    }
                    opening = task
                    assertTrue(withContext(Dispatchers.IO) { constructed.await(5, TimeUnit.SECONDS) },
                        "real owner construction reached its gate within 5s (failCleanup=$failCleanup)")
                    task.cancel(cancellation)
                    constructionRelease.countDown()
                    assertFailsWith<CancellationException> { task.await() }
                    val observed = assertNotNull(propagatedFailure.get())
                    assertCoroutineFailureIdentity(cancellation, observed)
                    if (failCleanup) assertCoroutineFailureIdentity(cleanupFailure, observed.suppressed.single())
                    else assertTrue(observed.suppressed.isEmpty(),
                        "non-failing owner cleanup must add no suppressed errors: " +
                            observed.suppressed.joinToString { failure ->
                                "${failure.javaClass.name}: ${failure.message}; " +
                                    "isExpectedCleanup=${failure === cleanupFailure}; " +
                                    "directCauseIsExpectedCleanup=${failure.cause === cleanupFailure}"
                            })
                    assertEquals(1, cleanupCalls.get(), "a constructed but unpublished store is disposed exactly once")
                    assertFalse(assertNotNull(openedDatabase.get()).isOpen,
                        "database disposal still happens when owner cleanup fails")
                    assertTrue(storeJob.isActive, "initialization cancellation does not own the process scope")
                    assertTrue(storeJob.children.none { it.isActive }, "the rejected owner leaves no repository job")
                    assertFailsWith<CancellationException> { task.await() }
                    assertEquals(1, cleanupCalls.get(), "observing cancellation again cannot replay disposal")
                } catch (failure: Throwable) {
                    caseFailure = failure
                    throw failure
                } finally {
                    constructionRelease.countDown()
                    cleanUpRoomOwnerTest(
                        caseFailure,
                        { opening?.cancelAndJoin() },
                        { withContext(Dispatchers.IO) { if (cleanupCalls.get() == 0) constructedStore.get()?.close() } },
                        { withContext(Dispatchers.IO) { openedDatabase.get()?.let { if (it.isOpen) it.close() } } },
                        { processJob.cancelAndJoin() },
                    )
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanUpRoomOwnerTest(primaryFailure, { Dispatchers.resetMain(); mainDispatcher.close() })
        }
    }

    private fun assertCoroutineFailureIdentity(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine debug stack recovery may copy an exception at withContext; its direct cause is the original.
        assertSame(expected, if (actual === expected) actual else actual.cause,
            "only the original failure or its direct coroutine recovery copy is accepted")
    }

    private suspend fun cleanUpRoomOwnerTest(primary: Throwable?, vararg operations: suspend () -> Unit) {
        withContext(NonCancellable) {
            var failure = primary
            for (operation in operations) {
                try { operation() }
                catch (cleanup: Throwable) {
                    val previous = failure
                    if (previous == null) failure = cleanup
                    else if (previous !== cleanup) previous.addSuppressed(cleanup)
                }
            }
            if (primary == null) failure?.let { throw it }
        }
    }
}
