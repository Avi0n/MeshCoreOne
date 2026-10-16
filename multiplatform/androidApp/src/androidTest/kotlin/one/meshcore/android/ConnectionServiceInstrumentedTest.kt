package one.meshcore.android

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.connection.DeviceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The W1 foreground-service exit-gate evidence. Proves the connection service starts on a connect intent
 * from a foregrounded app, survives an Activity recreation (the process-scoped graph stays resident), and
 * stops on the disconnect intent; and that it is declared with `foregroundServiceType=connectedDevice`.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionServiceInstrumentedTest {
    // Starting a connectedDevice foreground service requires a GRANTED qualifying runtime
    // permission at start time, not just the manifest declaration.
    @get:Rule
    val bluetoothPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestDeclaresConnectedDeviceForegroundType() {
        val component = ComponentName(context, ConnectionService::class.java)
        val info = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertTrue(
            "service must declare the connectedDevice foreground type",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
    }

    @Test
    fun notificationBuildsForEveryConnectionState() {
        ConnectionNotification.ensureChannel(context)
        DeviceConnectionState.entries.forEach { state ->
            assertNotNull(ConnectionNotification.build(context, ConnectionNotification.Content(state)))
        }
    }

    @Test
    fun theConnectionNotificationIsOngoingAndHasNoDeleteIntent() {
        ConnectionNotification.ensureChannel(context)
        val notification = ConnectionNotification.build(
            context,
            ConnectionNotification.Content(DeviceConnectionState.DISCONNECTED),
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.deleteIntent == null)
    }

    @Test
    fun serviceStaysUpWhileTheActivityIsStoppedAndTheDeviceIdles() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ConnectionService.start(context)
            assertTrue("service should start before the activity stops", awaitRunning(expected = true))
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue("service should stay up after the activity stops", awaitRunning(expected = true))
            try {
                shell("cmd deviceidle force-idle")
                assertTrue("service should stay up while the device is idle", awaitRunning(expected = true))
            } finally {
                shell("cmd deviceidle unforce")
                ConnectionService.stop(context)
                assertTrue("service should stop after idle", awaitRunning(expected = false))
            }
        }
    }

    @Test
    fun serviceStartsOnConnectSurvivesRecreationAndStopsOnDisconnect() = runBlocking {
        // A foregrounded Activity makes the foreground-service start lawful (API 31+ background-start rule).
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ConnectionService.start(context)
            assertTrue("service should start on the connect intent", awaitRunning(expected = true))

            scenario.recreate()
            assertTrue("service should survive an Activity recreation", awaitRunning(expected = true))

            ConnectionService.stop(context)
            assertTrue("service should stop on the disconnect intent", awaitRunning(expected = false))
        }
    }

    @Suppress("DEPRECATION") // getRunningServices returns this app's own services, which is all this checks.
    private fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = ConnectionService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == target }
    }

    private suspend fun awaitRunning(expected: Boolean): Boolean {
        val settled = withTimeoutOrNull(TIMEOUT_MS) {
            while (isServiceRunning() != expected) delay(POLL_MS)
            true
        }
        return settled == true
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
    }
}

/**
 * A [ConnectionService.start] while [Manifest.permission.BLUETOOTH_CONNECT] and
 * [Manifest.permission.POST_NOTIFICATIONS] are already denied. Revoking either permission from this
 * process kills it, so the host revokes both and force-stops the app before instrumentation starts.
 * Pass runner argument [REVOKE_PREPARED] only for that run. This start is not [ConnectionService.startAfterBoot].
 */
@RunWith(AndroidJUnit4::class)
class ConnectionServiceUngrantedInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceStartsWithoutBluetoothConnect() = runBlocking {
        assumeTrue(
            "host must revoke runtime permissions before this process starts",
            InstrumentationRegistry.getArguments().getString(REVOKE_PREPARED) == "true",
        )
        assertEquals(
            "BLUETOOTH_CONNECT must already be denied",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT),
        )
        assertEquals(
            "POST_NOTIFICATIONS must already be denied",
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            ConnectionService.start(context)
            assertTrue(
                "service should become foreground without BLUETOOTH_CONNECT",
                awaitForeground(),
            )
            logShade()
            ConnectionService.stop(context)
            assertTrue("service should stop on the disconnect intent", awaitRunning(expected = false))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = ConnectionService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == target }
    }

    @Suppress("DEPRECATION")
    private fun isForegroundService(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val target = ConnectionService::class.java.name
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == target && info.foreground
        }
    }

    private suspend fun awaitRunning(expected: Boolean): Boolean {
        val settled = withTimeoutOrNull(TIMEOUT_MS) {
            while (isServiceRunning() != expected) delay(POLL_MS)
            true
        }
        return settled == true
    }

    private suspend fun awaitForeground(): Boolean {
        val settled = withTimeoutOrNull(TIMEOUT_MS) {
            while (!isForegroundService()) delay(POLL_MS)
            true
        }
        return settled == true
    }

    private fun logShade() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val ids = manager.activeNotifications.joinToString { it.id.toString() }
        Log.i(
            LOG_TAG,
            "enabled=${manager.areNotificationsEnabled()} foreground=${isForegroundService()} ids=[$ids]",
        )
        Log.i(LOG_TAG, "services: ${filteredDump("dumpsys activity services ${context.packageName}")}")
        Log.i(LOG_TAG, "shade: ${filteredDump("dumpsys notification --noredact")}")
    }

    private fun filteredDump(command: String): String = shell(command)
        .lineSequence()
        .filter { line ->
            line.contains("isForeground") ||
                line.contains("foregroundId") ||
                line.contains("foregroundServiceType") ||
                line.contains("ConnectionService") ||
                line.contains(context.packageName)
        }
        .take(MAX_DUMP_LINES)
        .joinToString(" | ")

    private companion object {
        const val REVOKE_PREPARED = "revokePrepared"
        const val LOG_TAG = "ConnectionServiceTest"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
        const val MAX_DUMP_LINES = 40
    }
}

private fun shell(command: String): String {
    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
}
