package one.meshcore.android

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.activateLastBle
import one.meshcore.shared.connection.noteWantsConnection
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionServiceIntentInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private lateinit var fixture: ConnectionServiceIntentFixture

    @Before
    fun setUp() = runBlocking {
        fixture = ConnectionServiceIntentFixture()
        fixture.open()
    }

    @After
    fun tearDown() = runBlocking {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test
    fun explicitDisconnectSuppressesRestoreAndActivityForegroundService() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        fixture.container.intentStore.persist(ConnectionIntent.UserDisconnected)
        val restored = fixture.reads.capture()
        ConnectionService.startAfterBoot(fixture.context)
        restored.awaitFinished()
        fixture.record("explicit-disconnect-restore")
        fixture.assertServiceAbsent()
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.waitForMainIdle()
        fixture.reads.finishReaders()
        val foreground = fixture.reads.capture()
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        foreground.awaitFinished()
        fixture.waitForMainIdle()
        fixture.record("explicit-disconnect-foreground")
        fixture.assertServiceAbsent()
        assertTrue(fixture.container.intentStore.restore().isUserDisconnected)
        fixture.assertNoRadioOrQueueWork()
    }

    @Test
    fun explicitStartSurvivesUntilTheOldDisconnectBitIsCleared() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        fixture.container.intentStore.persist(ConnectionIntent.UserDisconnected)
        ConnectionService.start(fixture.context)
        fixture.assertServiceForeground()
        assertTrue(fixture.container.intentStore.restore().isUserDisconnected)
        withContext(Dispatchers.Main.immediate) { fixture.container.connectionManager.noteWantsConnection() }
        assertFalse(fixture.container.intentStore.restore().isUserDisconnected)
        fixture.record("explicit-start-before-intent-clear")
        fixture.assertServiceForeground()
        fixture.assertNoRadioOrQueueWork()
    }

    @Test
    fun wantedBleWithControlledAuthorizationDenialKeepsTheService() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        fixture.seedSavedRadio()
        withContext(Dispatchers.Main.immediate) {
            fixture.container.connectionManager.noteWantsConnection()
            try {
                fixture.container.connectionManager.activateLastBle(ConnectionServiceIntentRadio.HANDLE)
                error("the controlled authorization provider must reject BLE admission")
            } catch (_: BluetoothUnauthorizedException) {
                Unit
            }
        }
        val restored = fixture.reads.capture()
        ConnectionService.startAfterBoot(fixture.context)
        restored.awaitFinished()
        fixture.record("wanted-ble-controlled-denial")
        fixture.assertServiceForeground()
        assertEquals(ConnectionState.Disconnected, fixture.container.connectionManager.state.value)
        assertEquals(ConnectionServiceIntentRadio.DEVICE, fixture.container.lastConnectionStore.deviceId())
        assertEquals(ConnectionServiceIntentRadio.RADIO, fixture.container.lastConnectionStore.radioId())
        assertFalse(fixture.container.intentStore.restore().isUserDisconnected)
        fixture.assertNoRadioOrQueueWork()
    }

    @Test
    fun anOlderSkippedRestoreCannotStopANewerExplicitStart() = runBlocking {
        fixture.launchActivity()
        fixture.stopService()
        fixture.container.intentStore.persist(ConnectionIntent.UserDisconnected)
        val restored = fixture.reads.capture(held = true)
        try {
            ConnectionService.startAfterBoot(fixture.context)
            restored.awaitEntered()
            assertEquals(true, restored.value)
            ConnectionService.start(fixture.context)
            fixture.awaitSecondStartDelivered()
            restored.release.complete(Unit)
            restored.awaitFinished()
            fixture.record("older-restore-newer-explicit-start")
            fixture.assertServiceForeground()
            assertTrue(fixture.container.intentStore.restore().isUserDisconnected)
            withContext(Dispatchers.Main.immediate) { fixture.container.connectionManager.noteWantsConnection() }
            assertFalse(fixture.container.intentStore.restore().isUserDisconnected)
            fixture.assertNoRadioOrQueueWork()
        } finally {
            restored.release.complete(Unit)
        }
    }
}
