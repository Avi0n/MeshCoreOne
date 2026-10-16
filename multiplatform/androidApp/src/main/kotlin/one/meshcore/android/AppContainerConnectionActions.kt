package one.meshcore.android

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.app.connectSimulator
import one.meshcore.shared.app.reconnectLastDevice
import one.meshcore.shared.connection.DeviceConnectedToOtherAppException
import one.meshcore.shared.connection.SavedDeviceConnectError
import one.meshcore.shared.connection.classifySavedDeviceConnectError
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.connectSaved
import one.meshcore.shared.connection.connectViaWiFi
import one.meshcore.shared.connection.deleteSavedDevice
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.fetchSavedConnectableDevices
import one.meshcore.shared.connection.noteWantsConnection
import one.meshcore.shared.connection.savedDeviceIsConnectedElsewhere
import one.meshcore.shared.connection.setPairingInProgress
import one.meshcore.shared.connection.shouldRefuseSavedConnectForOtherApp
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.clearPendingLinks
import one.meshcore.shared.simulator.DemoInlineImageSeeder
import one.meshcore.shared.transport.systemConnectedBleHandles
import one.meshcore.shared.ui.ConnectionSavedDeviceActions
import one.meshcore.shared.ui.ConnectionSessionActions

internal class AppConnectionSessionActions : ConnectionSessionActions {
    internal lateinit var host: AppContainer

    override suspend fun connect(deviceId: String) = with(host) {
        ConnectionService.start(appContext)
        handleCompanionAssociationRemovals(companionDevices.refreshAssociationSnapshot())
        refuseIfHeldByOtherApp(handle = deviceId, lastHandle = lastConnectionStore.handle(), alertId = deviceId)
        appState.connect(deviceId)
        syncCompanionPresence()
    }
    override suspend fun connectViaWiFi(host: String, port: Int) = with(this.host) {
        ConnectionService.start(appContext)
        appState.connectViaWiFi(host, port)
        syncCompanionPresence()
    }
    override suspend fun connectSimulator() = with(host) {
        DemoInlineImageSeeder.seed(linkPreviewCache, inlineImageDimensions)
        ConnectionService.start(appContext)
        appState.connectSimulator()
        syncCompanionPresence()
    }

    /**
     * Reconnect the last radio. WiFi first from saved methods, then the BLE method handle.
     * Used by Quick Settings tile, shortcuts, and the connect sheet.
     */
    override suspend fun reconnectLastDevice(): Boolean = with(host) {
        ConnectionService.start(appContext)
        val reconnected = appState.reconnectLastDevice()
        syncCompanionPresence()
        return reconnected
    }
    override suspend fun lastDeviceName(): String? = with(host) { lastConnectionStore.deviceName() }
    override fun setPairingInProgress(inProgress: Boolean) = with(host) {
        connectionManager.setPairingInProgress(inProgress)
    }
    override suspend fun noteWantsConnection() = with(host) {
        connectionManager.noteWantsConnection()
        syncCompanionPresence()
        ConnectionService.start(appContext)
    }
    override suspend fun disconnect() = with(host) {
        appState.disconnect()
        syncCompanionPresence()
        ConnectionService.stop(appContext)
    }
    override suspend fun disconnectForPairing() = with(host) {
        appState.disconnectForPairing()
        syncCompanionPresence()
    }
    override suspend fun sendAdvert(flood: Boolean) = with(host) { sendSelfAdvert(flood, allowLocationPrompt = true) }
}

internal class AppConnectionSavedDeviceActions : ConnectionSavedDeviceActions {
    internal lateinit var host: AppContainer

    override suspend fun listSavedDevices() = with(host) { connectionManager.fetchSavedConnectableDevices() }
    override fun systemConnectedBleHandles(): Set<String> = with(host) { appContext.systemConnectedBleHandles() }
    override suspend fun lastConnectedDeviceId() = with(host) { lastConnectionStore.deviceId() }
    override fun presentConnectedElsewhereAlert(deviceId: String) = with(host) {
        connectionUi.presentSavedDeviceConnectFailure(deviceId, SavedDeviceConnectError.OtherApp)
    }
    override suspend fun connectSavedDevice(device: one.meshcore.shared.connection.SavedRadioDevice) = with(host) {
        ConnectionService.start(appContext)
        handleCompanionAssociationRemovals(companionDevices.refreshAssociationSnapshot())
        val elsewhere = savedDeviceIsConnectedElsewhere(
            device,
            connectionManager.state.value.toDeviceConnectionState(),
            systemConnectedBleHandles(),
        )
        if (shouldRefuseSavedConnectForOtherApp(
                connectsViaWiFi = device.connectsViaWiFi,
                isHeldByOtherApp = elsewhere,
                isLastConnectedRadio = device.id == lastConnectionStore.deviceId(),
            )
        ) {
            presentConnectedElsewhereAlert(device.id.value)
            throw DeviceConnectedToOtherAppException()
        }
        runSavedDeviceConnect(
            connect = {
                connectionManager.connectSaved(device)
                syncCompanionPresence()
            },
            presentFailure = { error ->
                savedDeviceFailureToPresent(error)?.let { failure ->
                    connectionUi.presentSavedDeviceConnectFailure(
                        device.id.value,
                        classifySavedDeviceConnectError(failure),
                    )
                }
            },
        )
    }
    override suspend fun deleteSavedDevice(deviceId: one.meshcore.shared.model.Uuid) = with(host) {
        connectionManager.deleteSavedDevice(deviceId) { handle ->
            companionDevices.disassociate(handle)
        }
        if (lastConnectionStore.radioId() == null) {
            offlineData.dropOfflinePartition()
        } else {
            offlineData.refresh()
        }
        notifyConversationsChanged()
        if (currentRadioId() == null) {
            coordinator.clearPendingLinks()
            refreshSendMessageShortcuts()
        }
    }
    override fun dismissAlert() = with(host) { connectionUi.dismissAlert() }
    override fun requestConnectPicker() = with(host) { connectionUi.requestConnectPicker() }
    override fun canDropOsBond(): Boolean = with(host) { companionDevices.canDropOsBond }
}

/** Cancellation propagates. Any other failure is reported, then rethrown. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runSavedDeviceConnect(connect: suspend () -> Unit, presentFailure: (Throwable) -> Unit) {
    try {
        connect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        presentFailure(error)
        throw error
    }
}

/** A radio already held by another app was alerted before connect. Do not alert again. */
internal fun savedDeviceFailureToPresent(error: Throwable): Throwable? =
    if (error is DeviceConnectedToOtherAppException) null else error
