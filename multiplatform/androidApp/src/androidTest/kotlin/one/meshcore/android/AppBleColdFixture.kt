package one.meshcore.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.chat.awaitDrainCompletion
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.model.MessageDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.security.MessageDigest

internal class AppBleColdFixture(val phase: String) {
    val checkpoints = AppBleColdCheckpoints(phase)
    private val application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MeshCoreApplication
    private val container = application.container
    private val manager = container.connectionManager
    private var scenario: ActivityScenario<MainActivity>? = null

    suspend fun prepare() {
        withContext(Dispatchers.Main.immediate) {
            container.awaitInitialOfflineData()
            assertIdle()
        }
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            assertEquals(PackageManager.PERMISSION_GRANTED, application.checkSelfPermission(permission))
        }
        assertEmpty()
        if (phase == "seed") {
            assertTrue(BackupPostcommitRows(container.database).snapshot().values.all { it.isEmpty() })
            assertNull(container.lastConnectionStore.deviceId())
            withContext(Dispatchers.Main.immediate) { container.onboarding.complete() }
        } else {
            assertIdentity()
            val disconnected = container.intentStore.restore() == ConnectionIntent.UserDisconnected
            assertEquals(phase == "suppressed", disconnected)
            if (phase == "suppressed") checkpoints.assertPreviousDisconnect()
            checkpoint("$phase-before-ui", null)
        }
    }

    suspend fun launch() = coroutineScope {
        val timeout = if (phase == "reconnect") CONNECT_TIMEOUT_MS else TIMEOUT_MS
        val foreground = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(timeout) { container.appForeground.first() }
        }
        withContext(Dispatchers.Main.immediate) { assertIdle() }
        if (phase != "seed") {
            assertIdentity()
            assertEquals(phase == "suppressed", container.intentStore.restore() == ConnectionIntent.UserDisconnected)
        }
        withContext(Dispatchers.Main.immediate) { assertIdle() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        checkNotNull(scenario).onActivity {
            assertSame(application, it.application)
            assertSame(container, (it.application as MeshCoreApplication).container)
        }
        foreground.await()
    }

    suspend fun connectSeed(): ServiceContainer {
        check(phase == "seed")
        withContext(Dispatchers.Main.immediate) {
            withTimeout(CONNECT_TIMEOUT_MS) { container.connect(checkpoints.handle) }
        }
        return ready()
    }

    suspend fun ready(): ServiceContainer {
        val owner = withContext(Dispatchers.Main.immediate) {
            assertSame(container, application.container)
            assertTrue(
                "Ready on the authorized radio",
                manager.state.value == ConnectionState.Ready(checkpoints.handle),
            )
            assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
            val current = checkNotNull(manager.services)
            assertTrue(current.isEventMonitoringActive)
            assertFalse(current.sendQueue.isRetired)
            assertEquals(current.radioId, manager.radioId)
            assertEquals(checkpoints.keyHash, digest(current.selfPublicKey))
            current
        }
        withTimeout(TIMEOUT_MS) { owner.sendQueue.awaitDrainCompletion() }
        assertIdentity()
        assertFalse(container.intentStore.restore() == ConnectionIntent.UserDisconnected)
        assertEmpty()
        withContext(Dispatchers.Main.immediate) {
            assertSame(owner, manager.services)
            assertEquals(owner.radioId, manager.radioId)
            assertTrue(owner.isEventMonitoringActive)
            assertFalse(owner.sendQueue.isRetired)
        }
        return owner
    }

    private suspend fun assertIdentity() {
        val row = container.database.deviceDao().listAll().single()
        assertEquals(checkpoints.keyHash, digest(row.publicKey))
        assertEquals(row.id, container.lastConnectionStore.deviceId())
        assertEquals(row.radioID, container.lastConnectionStore.radioId())
        assertTrue(row.connectionMethods.none { it is ConnectionMethod.WiFi })
        assertTrue(row.connectionMethods.any { it is ConnectionMethod.Bluetooth })
        assertTrue(
            "Saved descriptor selects the authorized radio",
            DeviceSelectionFilter.bleHandle(row.connectionMethods, container.lastConnectionStore.handle()) ==
                checkpoints.handle,
        )
        assertTrue(
            "Saved handle matches the authorized current radio",
            container.lastConnectionStore.handle() == checkpoints.handle,
        )
        if (phase != "seed") {
            val seed = checkpoints.seed()
            assertEquals(seed.getString("deviceId"), row.id.value)
            assertEquals(seed.getString("radioId"), row.radioID.value)
        }
    }

    suspend fun holdSeed() {
        check(phase == "seed")
        ready()
        val row = container.database.deviceDao().listAll().single()
        checkpoints.hold(row.id.value, row.radioID.value)
    }

    suspend fun checkpoint(stage: String, owner: ServiceContainer?) {
        assertStage(stage, owner)
        val row = container.database.deviceDao().listAll().single()
        checkpoints.publish(stage, row.id.value, row.radioID.value)
        assertStage(stage, owner)
    }

    private suspend fun assertStage(stage: String, owner: ServiceContainer?) {
        when (stage) {
            "reconnect-before-ui" -> {
                assertNull(owner)
                withContext(Dispatchers.Main.immediate) { assertIdle() }
                assertFalse(container.intentStore.restore() == ConnectionIntent.UserDisconnected)
            }
            "reconnect-ready" -> assertSame(checkNotNull(owner), ready())
            "reconnect-disconnected", "suppressed-before-ui", "suppressed-complete" -> {
                assertNull(owner)
                assertSuppressed()
            }
            else -> error("Unknown cold checkpoint")
        }
        assertIdentity()
        assertEmpty()
    }

    suspend fun disconnect(owner: ServiceContainer, click: () -> Unit) = coroutineScope {
        val retired = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(TIMEOUT_MS) { owner.sendQueue.retirementState.toList() }
        }
        click()
        assertEquals(listOf(false, true), retired.await())
        withTimeout(TIMEOUT_MS) {
            while (!withContext(Dispatchers.Main.immediate) {
                    manager.state.value == ConnectionState.Disconnected && manager.services == null
                }
            ) {
                delay(AppBleColdCheckpoints.POLL_MS)
            }
        }
        withContext(Dispatchers.Main.immediate) {
            assertTrue(owner.sendQueue.isRetired)
            assertFalse(owner.isEventMonitoringActive)
        }
        withTimeout(TIMEOUT_MS) { owner.sendQueue.awaitDrainCompletion() }
        assertSuppressed()
    }

    suspend fun assertSuppressed() {
        withContext(Dispatchers.Main.immediate) { assertIdle() }
        assertEquals(ConnectionIntent.UserDisconnected, container.intentStore.restore())
        assertIdentity()
        assertEmpty()
    }

    private fun assertIdle() {
        assertSame(container, application.container)
        assertTrue("Manager is disconnected", manager.state.value == ConnectionState.Disconnected)
        assertNull(manager.services)
        assertNull(manager.currentTransportType)
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

    suspend fun close() {
        try {
            if (scenario != null) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    withTimeout(TIMEOUT_MS) { container.disconnect() }
                }
            }
        } finally {
            scenario?.close()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val TIMEOUT_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 90_000L
    }
}
