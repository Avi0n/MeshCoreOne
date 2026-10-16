package one.meshcore.shared.connection

import one.meshcore.shared.settings.PersistentLogger

/** iOS `ConnectionManager.updateAutoAddConfig` persist-fail dump. */
const val FAILED_PERSIST_AUTO_ADD_CONFIG = "Failed to persist auto-add config"

/** iOS `ConnectionManager.updateClientRepeat` persist-fail dump. */
const val FAILED_PERSIST_CLIENT_REPEAT_STATE = "Failed to persist client repeat state"

/** iOS `ConnectionManager.updatePathHashMode` persist-fail dump. */
const val FAILED_PERSIST_PATH_HASH_MODE = "Failed to persist path hash mode"

/** iOS `ConnectionManager.updateDefaultFloodScopeName` persist-fail dump. */
const val FAILED_PERSIST_DEFAULT_FLOOD_SCOPE = "Failed to persist default flood scope"

/** iOS `ConnectionManager.addKnownRegion` persist-fail dump. */
const val FAILED_ADD_KNOWN_REGION = "Failed to add known region"

/** iOS `ConnectionManager.removeKnownRegion` persist-fail dump. */
const val FAILED_REMOVE_KNOWN_REGION = "Failed to remove known region"

/** iOS `Failed to clear default flood scope after region removal`. */
const val FAILED_CLEAR_DEFAULT_FLOOD_AFTER_REGION_REMOVAL =
    "Failed to clear default flood scope after region removal"

/** iOS `ConnectionManager.savePreRepeatSettings` persist-fail dump. */
const val FAILED_PERSIST_PRE_REPEAT_SETTINGS = "Failed to persist pre-repeat settings"

/** iOS `ConnectionManager.clearPreRepeatSettings` persist-fail dump. */
const val FAILED_PERSIST_CLEARED_PRE_REPEAT_SETTINGS =
    "Failed to persist cleared pre-repeat settings"

/** iOS `ConnectionManager.syncDeviceTimeIfNeeded` fail dump. */
const val FAILED_SYNC_DEVICE_TIME = "Failed to sync device time"

/** Device-row persist after a verified radio-params write. */
const val FAILED_PERSIST_RADIO_PARAMS = "Failed to persist radio params"

/** Device-row persist after a verified other-params write. */
const val FAILED_PERSIST_OTHER_PARAMS = "Failed to persist other params"

/** Device-row persist after a verified node-name write. */
const val FAILED_PERSIST_NODE_NAME = "Failed to persist node name"

/** Device-row persist after a BLE PIN write and reboot. */
const val FAILED_PERSIST_BLE_PIN = "Failed to persist BLE PIN"

/** Device-row persist after a verified location write. */
const val FAILED_PERSIST_DEVICE_COORDINATES = "Failed to persist device coordinates"

/** Device-row persist after a settings-screen live refresh. */
const val FAILED_PERSIST_DEVICE_SETTINGS_REFRESH = "Failed to persist device settings refresh"

/** Device-row persist after a WiFi host/port save. */
const val FAILED_PERSIST_WIFI_ENDPOINT = "Failed to persist WiFi endpoint"

internal fun failedToPersistDeviceSettingsLine(prefix: String, error: Throwable): String =
    "$prefix: ${error.message ?: error}"

internal fun failedToSyncDeviceTimeLine(error: Throwable): String =
    "$FAILED_SYNC_DEVICE_TIME: ${error.message ?: error}"

fun syncedDeviceTimeLine(driftSeconds: Int): String = "Synced device time (was off by ${driftSeconds}s)"

fun deviceTimeInSyncLine(driftSeconds: Int): String = "Device time in sync (drift: ${driftSeconds}s)"

/**
 * Persists a Device-row settings write after the radio already accepted it. A persist fault is
 * logged and swallowed so the caller does not report a failed write the radio already took.
 * Cancellation still stops the caller.
 */
suspend fun persistDeviceSettingsAfterRadioAck(
    logger: PersistentLogger?,
    failLinePrefix: String,
    persist: suspend () -> Unit,
) {
    runConnectionPersist(
        onFailure = { error -> logger?.error(failedToPersistDeviceSettingsLine(failLinePrefix, error)) },
        fallback = {},
        block = persist,
    )
}
