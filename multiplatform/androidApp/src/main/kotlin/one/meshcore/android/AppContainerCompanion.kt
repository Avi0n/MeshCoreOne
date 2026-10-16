package one.meshcore.android

import kotlinx.coroutines.launch
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.DeviceConnectedToOtherAppException
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.connection.bleHandleIsHeldByOtherApp
import one.meshcore.shared.connection.clearBondStamp
import one.meshcore.shared.connection.companionPresenceHandlesToObserve
import one.meshcore.shared.connection.deleteSavedDevice
import one.meshcore.shared.connection.fetchSavedConnectableDevices
import one.meshcore.shared.connection.savedDeviceMatchingCompanionHandle
import one.meshcore.shared.connection.shouldPresentOtherAppInsteadOfConnect
import one.meshcore.shared.connection.shouldStartConnectionOnRadioPresence
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.ScreenshotChatFixtures
import one.meshcore.shared.tools.DecryptStatus
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.newRxLogEntryId
import one.meshcore.shared.transport.systemConnectedBleHandles

/**
 * iOS `connect(to:)` throws `deviceConnectedToOtherApp` when another app holds the radio,
 * except the last-connected handle which tries to adopt the existing system GATT link.
 */
internal suspend fun AppContainer.refuseIfHeldByOtherApp(handle: String, lastHandle: String?, alertId: String) {
    val held = bleHandleIsHeldByOtherApp(
        handle,
        connectionManager.state.value.toDeviceConnectionState(),
        systemConnectedBleHandles(),
    )
    val isLast = lastHandle != null && lastHandle.equals(handle, ignoreCase = true)
    if (shouldPresentOtherAppInsteadOfConnect(held, isLast)) {
        presentConnectedElsewhereAlert(alertId)
        throw DeviceConnectedToOtherAppException()
    }
}
suspend fun AppContainer.syncCompanionPresence() {
    val handles = companionPresenceHandlesToObserve(
        associations = companionDevices.associations(),
        lastWantedBleHandle = lastConnectionStore.handle(),
        userDisconnected = intentStore.restore().isUserDisconnected,
    )
    companionDevices.syncObservedPresence(handles)
}
fun AppContainer.onCompanionDeviceAppeared(handle: String?) {
    appScope.launch {
        if (shouldStartConnectionOnRadioPresence(
                appearedHandle = handle,
                lastWantedBleHandle = lastConnectionStore.handle(),
                userDisconnected = intentStore.restore().isUserDisconnected,
            )
        ) {
            ConnectionService.startAfterBoot(appContext)
        }
    }
}

/**
 * iOS `pairing.removeDevice` before demote. Companion Device Manager `disassociate` is
 * silent; Connect Radio confirms with a Material dialog, Danger Zone with its own.
 */
internal suspend fun AppContainer.disassociateSavedDevice(deviceId: one.meshcore.shared.model.Uuid) {
    val lastId = lastConnectionStore.deviceId()
    val lastHandle = lastConnectionStore.handle()
    val entity = database.deviceDao().getById(deviceId) ?: return
    val handle = DeviceSelectionFilter.bleHandle(
        entity.connectionMethods,
        lastHandleIfThisDevice = lastHandle.takeIf { lastId == deviceId },
    )
    handle?.let { companionDevices.disassociate(it) }
}

/**
 * ASK `didRemoveDeviceWithID`: Settings unpair (or in-app disassociate) must demote the
 * Device row and stop auto-reconnect. `deleteSavedDevice` is idempotent if we already forgot.
 */
internal fun AppContainer.watchCompanionAssociationRemovals() {
    companionRemovalJob?.cancel()
    companionRemovalJob = appScope.launch {
        companionDevices.associationRemovals.collect { removed ->
            handleCompanionAssociationRemovals(removed)
        }
    }
}
internal suspend fun AppContainer.handleCompanionAssociationRemovals(removed: List<CompanionAssociation>) {
    if (removed.isEmpty()) return
    val saved = connectionManager.fetchSavedConnectableDevices()
    for (association in removed) {
        val device = savedDeviceMatchingCompanionHandle(saved, association.handle)
        if (device != null) {
            deleteSavedDevice(device.id)
        } else {
            connectionManager.clearBondStamp(association.handle)
        }
    }
}

internal suspend fun AppContainer.seedDemoRxLogEntries() {
    val radio = ScreenshotChatFixtures.RADIO_ID
    rxLogStoreImpl.clear(radio)
    val now = System.currentTimeMillis()
    val rows = demoFloodRxLogRows(radio, now) + demoDirectRxLogRows(radio, now)
    rows.forEach { rxLogStoreImpl.append(it) }
}

internal fun demoFloodRxLogRows(radio: one.meshcore.shared.model.Uuid, now: Long): List<RxLogEntry> = listOf(
    RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radio,
        receivedAtEpochMs = now,
        snr = 4.5,
        rssi = -82,
        routeType = 1,
        payloadType = 5,
        pathLength = 2,
        packetHash = "DEMOFLOOD001",
        packetPayloadSize = 48,
        decryptStatus = DecryptStatus.SUCCESS,
        regionScope = "Germany",
        channelIndex = 0,
        channelName = "Public",
        decodedText = "Alice: Rendezvous at checkpoint",
        senderTimestamp = (now / AppContainer.MILLIS_PER_SECOND).toLong(),
    ),
    RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radio,
        receivedAtEpochMs = now - 1_000L,
        snr = 1.2,
        rssi = -95,
        routeType = 0,
        payloadType = 5,
        pathLength = 3,
        packetHash = "DEMOFLOOD002",
        packetPayloadSize = 40,
        decryptStatus = DecryptStatus.NO_MATCHING_KEY,
        regionScope = null,
        channelIndex = null,
        channelName = null,
        decodedText = null,
    ),
)

internal fun demoDirectRxLogRows(radio: one.meshcore.shared.model.Uuid, now: Long): List<RxLogEntry> = listOf(
    RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radio,
        receivedAtEpochMs = now - 2_000L,
        snr = 7.0,
        rssi = -70,
        routeType = 2,
        payloadType = 2,
        pathLength = 0,
        packetHash = "DEMODIRECT01",
        packetPayloadSize = 32,
        decryptStatus = DecryptStatus.DM_NO_MATCHING_KEY,
        regionScope = null,
    ),
    RxLogEntry(
        id = newRxLogEntryId(),
        radioID = radio,
        receivedAtEpochMs = now - 3_000L,
        snr = 8.5,
        rssi = -65,
        routeType = 2,
        payloadType = 2,
        pathLength = 0,
        packetHash = "DEMODIRECTOK",
        packetPayloadSize = 48,
        decryptStatus = DecryptStatus.SUCCESS,
        regionScope = null,
        decodedText = "Bob: See you at rally point",
        senderTimestamp = (now / AppContainer.MILLIS_PER_SECOND).toLong() - 3,
    ),
)
