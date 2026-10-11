// AndroidOnly: WP-303 Android implementations of the process roles that have no merged production adapter.
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import android.os.UserManager
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.rendering.DraftDefaults
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/**
 * Notification delivery until the WP-401 platform adapter merges: authorization reads as denied and every post reports
 * the capability unsupported, so the notification policy contains it exactly like a refused permission and messaging is
 * never blocked.
 */
object UnavailableNotificationDelivery : NotificationDeliveryPort {
    override suspend fun authorizationStatus(): NotificationAuthorizationStatus = NotificationAuthorizationStatus.DENIED
    override suspend fun requestAuthorization(): Boolean = false
    override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>) = Unit
    override suspend fun post(request: NotificationRequest): NotificationPostResult =
        NotificationPostResult.Unsupported(Capability.NOTIFICATIONS)
    override suspend fun setBadgeCount(count: Long) = Unit
    override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> = SnapshotList.empty()
    override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) = Unit
}

/** Boolean flags (the favorites migration marker) in a private preference file; reads and writes are synchronous. */
class SharedPreferencesContactFlags(context: Context) : ContactPreferenceFlags {
    private val preferences = context.applicationContext.getSharedPreferences("mc1.contact-flags", Context.MODE_PRIVATE)
    override fun bool(key: String): Boolean = preferences.getBoolean(key, false)
    override fun set(key: String, value: Boolean) { preferences.edit { putBoolean(key, value) } }
}

/** The composer-draft dictionary as one JSON object under one key (Swift `UserDefaults` `[String: String]`). */
class SharedPreferencesDraftDefaults(context: Context) : DraftDefaults {
    private val preferences = context.applicationContext.getSharedPreferences("mc1.drafts", Context.MODE_PRIVATE)

    override fun stringDictionary(key: String): Map<String, String>? {
        val text = preferences.getString(key, null) ?: return null
        return try {
            val json = JSONObject(text)
            buildMap { json.keys().forEach { name -> put(name, json.getString(name)) } }
        } catch (malformed: JSONException) {
            null
        }
    }

    override fun setStringDictionary(value: Map<String, String>, key: String) {
        preferences.edit { putString(key, JSONObject(value).toString()) }
    }
}

/** Platform facts the foreground-service and presence routes read, evaluated at call time. */
class AndroidHostEnvironment(
    context: Context,
    private val reconnecting: () -> Boolean,
    private val lastDevice: () -> UUID?,
    private val lan: () -> Boolean,
) : HostEnvironment {
    private val context = context.applicationContext

    override val connectPermissionGranted: Boolean
        get() = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    override val userUnlocked: Boolean get() = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    override val bluetoothEnabled: Boolean
        get() = com.meshcoreone.android.app.platform.bluetoothAvailability(context) ==
            com.meshcoreone.android.core.ble.BluetoothAvailability.Ready
    override val reconnectInProgress: Boolean get() = reconnecting()
    override val lastConnectedDeviceId: UUID? get() = lastDevice()
    override val lanOnly: Boolean get() = lan()
}

internal fun usesLanHosting(
    transport: com.meshcoreone.android.core.model.TransportType?,
    methods: List<com.meshcoreone.android.core.model.ConnectionMethod>,
): Boolean = when (transport) {
    com.meshcoreone.android.core.model.TransportType.WIFI -> true
    com.meshcoreone.android.core.model.TransportType.BLUETOOTH -> false
    null -> methods.isNotEmpty() && methods.all { it is com.meshcoreone.android.core.model.ConnectionMethod.WiFi }
}
