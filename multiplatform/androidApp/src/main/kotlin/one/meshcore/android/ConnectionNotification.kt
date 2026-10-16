package one.meshcore.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo

/**
 * Ongoing connection foreground-service notification (Live Activity equivalent on Android).
 * Content reflects live connection state, optional radio name, battery, and unread count so the
 * shade matches the in-app status pill.
 */
internal object ConnectionNotification {
    const val CHANNEL_ID = "connection"
    const val NOTIFICATION_ID = 1001
    private const val SPEAK_REQUEST_CODE = 1002

    /** The live-connection fields the ongoing notification renders, mirrored from the app-state pill. */
    data class Content(
        val state: DeviceConnectionState,
        val nodeName: String? = null,
        val battery: BatteryInfo? = null,
        val batteryOcvArray: List<Int> = OCVPreset.LI_ION.ocvArray,
        val unreadCount: Int = 0,
        val packetsPerMinute: Int = 0,
        /** Cached spoken status line (iOS `StatusQueryIntent`); shown expanded and spoken on tap. */
        val spokenStatus: String? = null,
    )

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.notification_channel_connection)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null && existing.name == name) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, content: Content): Notification {
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            openApp,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = content.nodeName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val status = context.getString(content.state.statusTextRes())
        val contentText = buildContentText(context, status, content)
        val spoken = content.spokenStatus
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setNumber(content.unreadCount.coerceAtLeast(0))
        if (spoken != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(spoken))
            builder.addAction(speakStatusAction(context, spoken))
        }
        return builder.build()
    }

    private fun speakStatusAction(context: Context, spoken: String): NotificationCompat.Action {
        val speak = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SPEAK_STATUS
            putExtra(NotificationActionReceiver.EXTRA_SPOKEN_STATUS, spoken)
        }
        val pending = android.app.PendingIntent.getBroadcast(
            context,
            SPEAK_REQUEST_CODE,
            speak,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_lock_silent_mode_off,
            context.getString(R.string.notification_action_speak_status),
            pending,
        ).build()
    }

    private fun buildContentText(context: Context, status: String, content: Content): String {
        val parts = mutableListOf(status)
        // Packets/minute, the Live Activity's RX-freshness readout; shown while connected (iOS LockScreenView).
        if (content.state.isConnected) {
            parts.add(context.getString(R.string.notification_packet_rate, content.packetsPerMinute))
        }
        if (content.battery != null) {
            parts.add(BatteryDisplay.percentText(content.battery, content.batteryOcvArray))
        }
        if (content.unreadCount > 0) {
            parts.add(context.getString(R.string.notification_unread_count, content.unreadCount))
        }
        return parts.joinToString(separator = ", ")
    }

    private fun DeviceConnectionState.statusTextRes(): Int = when (this) {
        DeviceConnectionState.DISCONNECTED -> R.string.notification_state_disconnected
        DeviceConnectionState.CONNECTING -> R.string.notification_state_connecting
        DeviceConnectionState.CONNECTED, DeviceConnectionState.SYNCING -> R.string.notification_state_syncing
        DeviceConnectionState.READY -> R.string.notification_state_connected
    }
}
