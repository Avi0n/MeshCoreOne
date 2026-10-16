package one.meshcore.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import one.meshcore.shared.app.AutoReconnectDecision
import one.meshcore.shared.app.activate
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.isConnectionWanted
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.notification.StatusQueryCopies
import one.meshcore.shared.notification.StatusQuerySnapshot
import one.meshcore.shared.notification.statusQueryDialogText
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_intent_status_dialog_connected_no_battery
import one.meshcore.shared.resources.tools_intent_status_dialog_connected_with_battery
import one.meshcore.shared.resources.tools_intent_status_dialog_connecting
import one.meshcore.shared.resources.tools_intent_status_dialog_disconnected_named
import one.meshcore.shared.resources.tools_intent_status_dialog_disconnected_unknown
import one.meshcore.shared.resources.tools_intent_status_dialog_not_ready
import one.meshcore.shared.resources.tools_intent_status_radio_fallback_name
import org.jetbrains.compose.resources.getString

/**
 * Foreground service owning the live connection lifetime. Mirrors connection state, battery, node
 * name, and unread count into the ongoing notification (Live Activity equivalent).
 */
class ConnectionService : LifecycleService() {
    private val graph get() = (application as MeshCoreApplication).container
    private var notificationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ConnectionNotification.ensureChannel(this)

        if (intent?.action == ACTION_STOP) {
            retireStart(startId)
            return START_NOT_STICKY
        }

        promoteToForeground()
        // START_STICKY restarts with a null intent after process death. A boot start uses
        // ACTION_BOOT_RECONNECT. Both reconnect the last radio the way launch activate does;
        // do not claim the in-app foreground gate (that would suppress banners).
        if (shouldReconnectAfterStickyRestart(intent) || shouldReconnectAfterBootStart(intent?.action)) {
            lifecycleScope.launch {
                val decision = graph.appState.activate()
                val mayRetire = decision == AutoReconnectDecision.SKIP_USER_DISCONNECTED ||
                    decision == AutoReconnectDecision.NOTHING_TO_RECONNECT
                if (mayRetire && !graph.connectionManager.isConnectionWanted()) {
                    retireStart(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        startTypedForeground(currentConnectionNotification())
        if (notificationJob?.isActive == true) return
        collectConnectionNotification(getSystemService(NotificationManager::class.java))
    }

    private fun retireStart(startId: Int) {
        if (!stopSelfResult(startId)) return
        notificationJob?.cancel()
        notificationJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun currentConnectionNotification() = ConnectionNotification.build(
        context = this,
        content = ConnectionNotification.Content(
            state = graph.appState.connectionState.value,
            nodeName = graph.localNodeName.value,
            battery = graph.appState.battery.value,
            batteryOcvArray = graph.appState.batteryOcvArray.value,
            unreadCount = graph.unreadCount.value,
            packetsPerMinute = graph.packetsPerMinute.value,
        ),
    )

    // Prefer typed connectedDevice FGS (BLE + WiFi). Android 14+ requires a qualifying
    // permission (BLUETOOTH_CONNECT and/or CHANGE_WIFI_STATE). If the platform rejects the
    // typed start, fall back to an untyped foreground service so connect still works.
    private fun startTypedForeground(notification: Notification) {
        val preferredType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                ConnectionNotification.NOTIFICATION_ID,
                notification,
                preferredType,
            )
        } catch (e: SecurityException) {
            graph.connectionLogger.warning(
                "Foreground service type $preferredType denied; retrying untyped: ${e::class.simpleName}",
            )
            ServiceCompat.startForeground(
                this,
                ConnectionNotification.NOTIFICATION_ID,
                notification,
                // foregroundServiceType
                0,
            )
        }
    }

    private fun collectConnectionNotification(manager: NotificationManager) {
        notificationJob = lifecycleScope.launch {
            val copies = statusQueryCopies()
            val baseContent = combine(
                graph.appState.connectionState,
                graph.localNodeName,
                graph.appState.battery,
                graph.appState.batteryOcvArray,
                graph.unreadCount,
            ) { state: DeviceConnectionState, name: String?, battery: BatteryInfo?, ocvArray: List<Int>, unread: Int ->
                ConnectionNotification.Content(state, name, battery, ocvArray, unread)
            }
            // Second stage: fold in the packet rate so the shade tracks RX freshness and its decay.
            combine(baseContent, graph.packetsPerMinute) { content, ppm ->
                content.copy(packetsPerMinute = ppm)
            }.collect { content ->
                val spoken = statusQueryDialogText(
                    StatusQuerySnapshot(
                        appReady = true,
                        state = content.state,
                        connectedName = content.nodeName,
                        offlineName = graph.lastDeviceName(),
                        battery = content.battery,
                        ocvArray = content.batteryOcvArray,
                    ),
                    copies,
                )
                currentCoroutineContext().ensureActive()
                manager.notify(
                    ConnectionNotification.NOTIFICATION_ID,
                    ConnectionNotification.build(
                        this@ConnectionService,
                        content.copy(spokenStatus = spoken),
                    ),
                )
            }
        }
    }

    private suspend fun statusQueryCopies(): StatusQueryCopies = StatusQueryCopies(
        notReady = getString(Res.string.tools_intent_status_dialog_not_ready),
        connectedWithBattery = getString(Res.string.tools_intent_status_dialog_connected_with_battery),
        connectedNoBattery = getString(Res.string.tools_intent_status_dialog_connected_no_battery),
        connecting = getString(Res.string.tools_intent_status_dialog_connecting),
        disconnectedNamed = getString(Res.string.tools_intent_status_dialog_disconnected_named),
        disconnectedUnknown = getString(Res.string.tools_intent_status_dialog_disconnected_unknown),
        radioFallbackName = getString(Res.string.tools_intent_status_radio_fallback_name),
    )

    companion object {
        private const val ACTION_STOP = "one.meshcore.android.action.STOP_CONNECTION"

        /** Starts the service in the foreground; call when the app initiates a connection. */
        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            context.startForegroundService(intent)
        }

        /** Starts the service after a reboot so the last wanted radio comes back without a tap. */
        fun startAfterBoot(context: Context) {
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_BOOT_RECONNECT)
            context.startForegroundService(intent)
        }

        /** Stops the foreground service; call on an explicit user disconnect. */
        fun stop(context: Context) {
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

/**
 * True when the system restarted this service after process death (`START_STICKY` delivers a null
 * intent). A user-started connect carries a non-null intent and must not re-run launch activate.
 */
internal fun shouldReconnectAfterStickyRestart(intent: Intent?): Boolean = intent == null

/** System reboot broadcast. The receiver maps this onto [ACTION_BOOT_RECONNECT]. */
internal const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

/** Play/package replace. The FGS dies with the old process and START_STICKY does not fire. */
internal const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

/** Service start after a reboot or app update. Distinct from a user-started connect so activate runs. */
internal const val ACTION_BOOT_RECONNECT = "one.meshcore.android.action.BOOT_RECONNECT"

internal fun shouldStartConnectionAfterBoot(action: String?): Boolean =
    action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED

internal fun shouldReconnectAfterBootStart(action: String?): Boolean = action == ACTION_BOOT_RECONNECT
