package one.meshcore.shared.settings

import one.meshcore.shared.prefs.PreferencesStore

/** Which GPS feeds the node's advertised position. Raw values pinned to the Swift `GPSSource` enum. */
enum class GpsSource(val rawValue: String) {
    PHONE("phone"),
    DEVICE("device"),
    ;

    companion object {
        fun fromRaw(raw: String?): GpsSource? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The on-board GPS capability and state a radio reports, mirroring the Swift `DeviceGPSState`. */
data class DeviceGpsState(val isSupported: Boolean, val isEnabled: Boolean) {
    companion object {
        val unsupported = DeviceGpsState(isSupported = false, isEnabled = false)
    }
}

/**
 * Per-radio location preferences: whether the node's position follows a GPS, and which one. Scoped by
 * the Device-row id and stored under the same key strings as the Swift `DevicePreferenceStore`, so a
 * radio carries the same settings whichever platform paired it.
 *
 * Auto-update defaults off and the source defaults to the phone; "never chosen" stays distinguishable
 * from an explicit phone choice via [hasSetGpsSource], because the first connection to a radio whose own
 * GPS is already on adopts [GpsSource.DEVICE] rather than overriding the user.
 */
class DeviceLocationPreferences(private val store: PreferencesStore) {
    suspend fun isAutoUpdateLocationEnabled(deviceId: String): Boolean =
        store.getBoolean(autoUpdateLocationKey(deviceId)) ?: DEFAULT_AUTO_UPDATE_LOCATION

    suspend fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: String) {
        store.putBoolean(autoUpdateLocationKey(deviceId), enabled)
    }

    suspend fun gpsSource(deviceId: String): GpsSource =
        GpsSource.fromRaw(store.getString(gpsSourceKey(deviceId))) ?: DEFAULT_GPS_SOURCE

    suspend fun hasSetGpsSource(deviceId: String): Boolean = store.getString(gpsSourceKey(deviceId)) != null

    suspend fun setGpsSource(source: GpsSource, deviceId: String) {
        store.putString(gpsSourceKey(deviceId), source.rawValue)
    }

    private fun autoUpdateLocationKey(deviceId: String) = "$KEY_PREFIX$deviceId.autoUpdateLocation"

    private fun gpsSourceKey(deviceId: String) = "$KEY_PREFIX$deviceId.gpsSource"

    companion object {
        const val DEFAULT_AUTO_UPDATE_LOCATION = false
        val DEFAULT_GPS_SOURCE = GpsSource.PHONE

        private const val KEY_PREFIX = "device."
    }
}
