package one.meshcore.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.chat.awaitDrainCompletion
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AppBleQueueFixture {
    private val checkpoints = AppBleQueueCheckpoints()
    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MeshCoreApplication
    private val container = application.container
    private val manager = container.connectionManager
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private var deviceId: Uuid? = null

    suspend fun open() {
        withContext(Dispatchers.Main.immediate) {
            container.awaitInitialOfflineData()
            assertEquals(ConnectionState.Disconnected, manager.state.value)
            assertNull(manager.services)
            assertNull(container.lastConnectionStore.deviceId())
            container.onboarding.complete()
        }
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            assertEquals(PackageManager.PERMISSION_GRANTED, application.checkSelfPermission(permission))
        }
        val initial = BackupPostcommitRows(container.database).snapshot()
        assertTrue(initial.values.all { it.isEmpty() })
        foreground {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            checkNotNull(scenario).onActivity { activity = it }
        }
        assertEmpty()
        checkpoint("offline", null)
    }

    suspend fun connect(): ServiceContainer {
        assertEmpty()
        withContext(Dispatchers.Main.immediate) {
            withTimeout(CONNECT_TIMEOUT_MS) { container.connect(checkpoints.handle) }
        }
        val owner = ready()
        withTimeout(TIMEOUT_MS) { owner.sendQueue.awaitDrainCompletion() }
        assertSame(owner, ready())
        assertEmpty()
        return owner
    }

    suspend fun ready(): ServiceContainer {
        val owner = withContext(Dispatchers.Main.immediate) {
            assertSame(container, application.container)
            assertSame(manager, application.container.connectionManager)
            assertEquals(ConnectionState.Ready(checkpoints.handle), manager.state.value)
            assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
            val current = checkNotNull(manager.services)
            assertEquals(current.radioId, manager.radioId)
            assertTrue(current.isEventMonitoringActive)
            assertFalse(current.sendQueue.isRetired)
            assertEquals(checkpoints.keyHash, digest(current.selfPublicKey))
            current
        }
        val row = container.database.deviceDao().listAll().single()
        assertEquals(owner.radioId, row.radioID)
        assertEquals(checkpoints.keyHash, digest(row.publicKey))
        assertEquals(RADIO_NAME, row.nodeName)
        if (deviceId == null) deviceId = row.id
        assertEquals(deviceId, row.id)
        withContext(Dispatchers.Main.immediate) {
            assertEquals(ConnectionState.Ready(checkpoints.handle), manager.state.value)
            assertSame(owner, manager.services)
            assertTrue(owner.isEventMonitoringActive)
            assertFalse(owner.sendQueue.isRetired)
        }
        return owner
    }

    suspend fun recreate(owner: ServiceContainer) {
        assertSame(owner, ready())
        val previous = checkNotNull(activity)
        foreground {
            checkNotNull(scenario).recreate()
            checkNotNull(scenario).onActivity {
                assertNotSame(previous, it)
                assertSame(application, it.application)
                assertSame(container, (it.application as MeshCoreApplication).container)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                activity = it
            }
        }
        assertTrue(previous.isDestroyed)
        assertSame(owner, ready())
        assertEmpty()
    }

    suspend fun disconnect(owner: ServiceContainer, click: ((String) -> Unit) -> Unit) {
        val trace = DisconnectTrace()
        try {
            coroutineScope {
                assertSame(owner, ready())
                trace.note("retirement-subscription")
                val retirement = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(TIMEOUT_MS) {
                        owner.sendQueue.retirementState.onEach { trace.note("retirement-$it") }.toList()
                    }
                }
                retirement.invokeOnCompletion { trace.note("retirement-completed", failureKind(it)) }
                click { trace.note(it) }
                trace.note("retirement-await")
                assertEquals(listOf(false, true), retirement.await())
                trace.note("manager-await")
                withTimeout(TIMEOUT_MS) {
                    while (!withContext(Dispatchers.Main.immediate) {
                            manager.state.value == ConnectionState.Disconnected && manager.services == null
                        }
                    ) {
                        delay(AppBleQueueCheckpoints.POLL_MS)
                    }
                }
                trace.note("manager-disconnected")
                withContext(Dispatchers.Main.immediate) {
                    assertSame(container, application.container)
                    assertSame(manager, container.connectionManager)
                    assertTrue(owner.sendQueue.isRetired)
                    assertFalse(owner.isEventMonitoringActive)
                }
                trace.note("drain-await")
                withTimeout(TIMEOUT_MS) { owner.sendQueue.awaitDrainCompletion() }
                trace.note("drain-completed")
                assertEmpty()
            }
        } catch (failure: Throwable) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val handler = Handler(Looper.getMainLooper())
                    val snapshot = observeWithinDeadline(
                        timeoutMs = OBSERVATION_TIMEOUT_MS,
                        post = { handler.post(it) },
                        remove = { handler.removeCallbacks(it) },
                    ) {
                        JSONObject().put("snapshotElapsedRealtimeMs", SystemClock.elapsedRealtime())
                            .put("oldQueueRetired", owner.sendQueue.isRetired)
                            .put("oldMonitoringActive", owner.isEventMonitoringActive)
                            .put("managerServicesIsExpected", manager.services === owner)
                            .put("managerServicesIsNull", manager.services == null)
                            .put(
                                "managerTransport",
                                when (manager.currentTransportType) {
                                    TransportType.BLUETOOTH -> "bluetooth"
                                    TransportType.WIFI -> "wifi"
                                    null -> "none"
                                },
                            )
                            .put(
                                "managerState",
                                when (manager.state.value) {
                                    ConnectionState.Disconnected -> "disconnected"
                                    is ConnectionState.Connecting -> "connecting"
                                    is ConnectionState.Connected -> "connected"
                                    is ConnectionState.Syncing -> "syncing"
                                    is ConnectionState.Ready -> "ready"
                                    is ConnectionState.Reconnecting -> "reconnecting"
                                    is ConnectionState.BondLost -> "bond-lost"
                                },
                            )
                    }
                    val record = JSONObject().put("events", trace.snapshot())
                        .put("postFailureObservation", true).put("beforeFallbackDisconnect", true)
                        .put("failureKind", failureKind(failure))
                        .put("snapshotAvailable", snapshot != null)
                        .put("snapshot", snapshot ?: JSONObject.NULL)
                    checkpoints.publishDisconnectFailure(record)
                }
            } catch (diagnosticFailure: Throwable) {
                failure.addSuppressed(diagnosticFailure)
            }
            throw failure
        }
    }

    private fun observeWithinDeadline(
        timeoutMs: Long,
        post: (Runnable) -> Boolean,
        remove: (Runnable) -> Unit,
        capture: () -> JSONObject,
    ): JSONObject? {
        val accepting = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        val value = AtomicReference<JSONObject?>(null)
        val read = Runnable {
            if (accepting.get()) {
                try {
                    val snapshot = capture()
                    if (accepting.get()) value.set(snapshot)
                } catch (_: Throwable) {
                    value.set(null)
                } finally {
                    completed.countDown()
                }
            }
        }
        return try {
            if (post(read) && completed.await(timeoutMs, TimeUnit.MILLISECONDS)) value.get() else null
        } finally {
            accepting.set(false)
            remove(read)
        }
    }

    fun verifyFailureObservation() {
        var held: Runnable? = null
        var captures = 0
        val expired = observeWithinDeadline(
            timeoutMs = HELD_OBSERVATION_TIMEOUT_MS,
            post = {
                held = it
                true
            },
            remove = {},
        ) {
            captures++
            JSONObject()
        }
        assertNull(expired)
        checkNotNull(held).run()
        assertEquals(0, captures)
        val rejected = observeWithinDeadline(HELD_OBSERVATION_TIMEOUT_MS, { false }, {}) {
            captures++
            JSONObject()
        }
        assertNull(rejected)
        assertEquals(0, captures)
        val immediate = observeWithinDeadline(HELD_OBSERVATION_TIMEOUT_MS, {
            it.run()
            true
        }, {}) { JSONObject() }
        assertTrue(immediate != null)
    }

    private class DisconnectTrace {
        private val events = mutableListOf<JSONObject>()

        @Synchronized
        fun note(stage: String, failureKind: String = "none") {
            events.add(
                JSONObject().put("stage", stage).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                    .put("failureKind", failureKind),
            )
        }

        @Synchronized
        fun snapshot(): JSONArray = JSONArray(events.toList())
    }

    suspend fun checkpoint(stage: String, owner: ServiceContainer?) {
        assertEmpty()
        assertCheckpointOwner(owner)
        checkpoints.publish(stage)
        assertCheckpointOwner(owner)
    }

    private suspend fun assertCheckpointOwner(owner: ServiceContainer?) {
        if (owner != null) {
            assertSame(owner, ready())
        } else {
            withContext(Dispatchers.Main.immediate) {
                assertEquals(ConnectionState.Disconnected, manager.state.value)
                assertNull(manager.services)
            }
        }
    }

    suspend fun assertReplacement(old: ServiceContainer, fresh: ServiceContainer) =
        withContext(Dispatchers.Main.immediate) {
            assertNotSame(old, fresh)
            assertNotSame(old.sendQueue, fresh.sendQueue)
            assertEquals(old.radioId, fresh.radioId)
            assertTrue(old.selfPublicKey.contentEquals(fresh.selfPublicKey))
            assertTrue(old.sendQueue.isRetired)
            assertFalse(old.isEventMonitoringActive)
            assertSame(fresh, ready())
        }

    private suspend fun assertEmpty() {
        val outgoing = container.database.messageDao().listAll().count {
            it.directionRawValue == MessageDirection.OUTGOING.raw
        }
        assertEquals(0, outgoing)
        container.database.useConnection(isReadOnly = true) { connection ->
            for (query in listOf(
                "SELECT COUNT(*) FROM pending_send",
                "SELECT COUNT(*) FROM room_message WHERE isFromSelf = 1",
            )) {
                connection.usePrepared(query) {
                    assertTrue(it.step())
                    assertEquals(0L, it.getLong(0))
                    assertFalse(it.step())
                }
            }
        }
    }

    private suspend fun foreground(action: () -> Unit) = coroutineScope {
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { container.appForeground.first() }
        }
        action()
        foreground.await()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun close() {
        try {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                withTimeout(TIMEOUT_MS) {
                    if (scenario != null) {
                        container.disconnect()
                    }
                }
            }
        } finally {
            scenario?.close()
        }
    }

    companion object {
        private const val OBSERVATION_TIMEOUT_MS = 1000L
        private const val HELD_OBSERVATION_TIMEOUT_MS = 10L
        private fun failureKind(failure: Throwable?): String = when (failure) {
            null -> "none"
            is TimeoutCancellationException -> "timeout"
            is CancellationException -> "cancelled"
            is AssertionError -> "assertion"
            else -> "other"
        }
        const val TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 90_000L
        const val RADIO_NAME = "test2"
    }
}
