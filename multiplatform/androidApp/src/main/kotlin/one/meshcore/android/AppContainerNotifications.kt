package one.meshcore.android

import androidx.core.app.NotificationManagerCompat
import one.meshcore.shared.connection.connectedDeviceRowId
import one.meshcore.shared.connection.connectionSettingsSnapshot
import one.meshcore.shared.connection.deviceOcvEditorState
import one.meshcore.shared.connection.deviceOtherParamsSnapshot
import one.meshcore.shared.connection.locationSettingsSnapshot
import one.meshcore.shared.connection.nodesAutoAddSnapshot
import one.meshcore.shared.connection.radioSnapshot
import one.meshcore.shared.settings.RegionSelectionStore

internal suspend fun AppContainer.invalidateLists() {
    listInvalidations.emit(Unit)
}

/** Production hook for room mark-read (iOS SyncCoordinator version bump). */
suspend fun AppContainer.notifyConversationsChanged() {
    invalidateLists()
    // iOS clears unread then updateBadgeCount. setActive recomputes the badge before
    // mark-read zeros the rows, so this second pass is the one that lands the right count.
    notificationCoordinator.updateBadge()
}

/** Live OS notification-permission state, for the notification coordinator and Settings → Notifications. */
fun AppContainer.isNotificationsAuthorized(): Boolean =
    NotificationManagerCompat.from(appContext).areNotificationsEnabled()
internal suspend fun AppContainer.refreshSettingsSnapshotCache(connected: Boolean) {
    settingsSnapshots.region = RegionSelectionStore.load(prefs)
    if (!connected) {
        settingsSnapshots.clearRadioOwned()
        return
    }
    settingsSnapshots.radio = connectionManager.radioSnapshot()
    settingsSnapshots.location = connectionManager.locationSettingsSnapshot()
    settingsSnapshots.connection = connectionManager.connectionSettingsSnapshot()
    settingsSnapshots.nodesAutoAdd = connectionManager.nodesAutoAddSnapshot()
    settingsSnapshots.otherParams = connectionManager.deviceOtherParamsSnapshot()
    settingsSnapshots.ocv = connectionManager.deviceOcvEditorState()
    settingsSnapshots.deviceRowId = connectionManager.connectedDeviceRowId()?.value
}
