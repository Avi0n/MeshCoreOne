package one.meshcore.android

import kotlinx.coroutines.delay
import one.meshcore.shared.connection.FAILED_CLEAR_LAST_CONNECTION
import one.meshcore.shared.connection.applyForgetDevicePersist
import one.meshcore.shared.connection.applyLastConnectionPersist
import one.meshcore.shared.connection.clearBondStamp
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.disconnectBeforeForget
import one.meshcore.shared.connection.setPairingInProgress
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.navigation.clearPendingLinks
import one.meshcore.shared.persistence.deleteDeviceRow
import one.meshcore.shared.persistence.demoteDeviceToGhost
import one.meshcore.shared.persistence.purgeRadioPartition
import one.meshcore.shared.settings.runDangerZoneFactoryReset

/**
 * Forget the currently connected (or last) radio: disconnect, clear reconnect intent, and either
 * demote the device row (keep data) or purge the radio partition (delete data).
 */
suspend fun AppContainer.forgetKeepData() {
    forgetDevice(deleteData = false)
}
suspend fun AppContainer.forgetDeleteData() {
    forgetDevice(deleteData = true)
}
suspend fun AppContainer.factoryReset(): Boolean {
    val session = connectionManager.services?.session as? MeshCoreSession
        ?: return false
    return runDangerZoneFactoryReset(
        servicesAvailable = true,
        sendReset = { session.factoryReset() },
        afterExpectedReboot = {
            // The radio reboots before it ACKs. Wait so the command can land
            // before we tear down the link (iOS `resetRebootGracePeriod`).
            delay(AppContainer.FACTORY_RESET_REBOOT_GRACE_MS)
            forgetDevice(deleteData = true)
        },
    )
}
internal suspend fun AppContainer.forgetDevice(deleteData: Boolean) {
    val radioId = connectionManager.radioId ?: lastConnectionStore.radioId()
    val deviceId = lastConnectionStore.deviceId()
    val handle = lastConnectionStore.handle()
    connectionManager.setPairingInProgress(true)
    try {
        if (handle != null) {
            connectionManager.clearBondStamp(handle)
            companionDevices.disassociate(handle)
        }
        if (deviceId != null) disassociateSavedDevice(deviceId)
        applyLastConnectionPersist(
            persist = { lastConnectionStore.clear() },
            logger = connectionLogger,
            failLine = FAILED_CLEAR_LAST_CONNECTION,
        )
        // Drop the cached partition before disconnect so a connection-state refresh cannot restore it.
        offlineData.dropOfflinePartition()
        disconnectBeforeForget(connectionLogger::warning) { connectionManager.disconnect() }
        // Disconnect is a no-op when already offline, so the Chats list would keep the last
        // snapshot. Notify and pop radio-scoped screens the way a live disconnect does.
        notifyConversationsChanged()
        coordinator.clearPendingLinks()
        refreshSendMessageShortcuts()
        if (deviceId != null) {
            applyForgetDevicePersist(
                deleteData = deleteData,
                persist = {
                    if (deleteData) {
                        deleteDeviceRow(database, deviceId)
                    } else {
                        demoteDeviceToGhost(database, deviceId)
                    }
                },
                logger = connectionLogger,
            )
        }
        if (deleteData && radioId != null) {
            applyForgetDevicePersist(
                deleteData = true,
                persist = {
                    purgeRadioPartition(database, radioId)
                    // The RX log goes with the device, as iOS deletes its entries alongside the Device row.
                    rxLogStoreImpl.clear(radioId)
                },
                logger = connectionLogger,
            )
        }
    } finally {
        connectionManager.setPairingInProgress(false)
    }
}
