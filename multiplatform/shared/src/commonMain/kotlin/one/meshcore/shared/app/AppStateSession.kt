package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.PairingFailure
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.navigation.clearPendingLinks
import one.meshcore.shared.navigation.clearPerRadioSelection
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.runExceptionFallback

/**
 * Tears down staged navigation with the connection, mirroring iOS `wireServicesIfConnected`: the
 * services-gone branch runs the same `clearPendingLinks` the loss path and an explicit disconnect
 * both reach, and a live connection on a different radio runs `clearPerRadioSelection`. A staged
 * destination is scoped to the radio it was staged against, so replaying it under another radio would
 * aim a detail pane at a partition the target does not exist in.
 *
 * The radio is compared on [ConnectionManager.radioId], the data-partition key every per-radio row is
 * filtered by, rather than on the volatile transport handle iOS compares: re-pairing one radio mints a
 * new handle but keeps the partition, and that is not a switch.
 */
internal fun AppStateViewModel.clearStagedNavigation(state: ConnectionState, services: ServiceContainer?) {
    val isCurrentOwner = { connectionManager.state.value == state && connectionManager.services === services }
    if (!isCurrentOwner()) return
    if (services != null) {
        val radioId = connectionManager.radioId
        if (navigatedRadioId != null && radioId != navigatedRadioId) {
            nodeDiscoveryWorkspace?.reset(isCurrentOwner)
            tracePathWorkspace?.reset(isCurrentOwner)
            remoteAdminWorkspaces?.reset(isCurrentOwner)
            navigation.clearPerRadioSelection()
        }
        navigatedRadioId = radioId
        if (state is ConnectionState.Ready) {
            nodeDiscoveryWorkspace?.reattach(isCurrentOwner)
            tracePathWorkspace?.reattach(isCurrentOwner)
            remoteAdminWorkspaces?.reattach(isCurrentOwner)
        }
    } else if (state is ConnectionState.Disconnected || state is ConnectionState.BondLost) {
        // Only a resting drop clears. Kotlin rebuilds the container on every connect, so a
        // reconnect and a device switch both pass through here with the services already gone;
        // iOS tears the session down for an auto-reconnect without firing `onConnectionLost`, and
        // ejecting a responder from an open conversation on every reconnect would not mirror it.
        nodeDiscoveryWorkspace?.reset(isCurrentOwner)
        tracePathWorkspace?.reset(isCurrentOwner)
        remoteAdminWorkspaces?.reset(isCurrentOwner)
        navigation.clearPendingLinks()
    }
}

internal fun AppStateViewModel.rebindBatteryCollector(session: MeshSession?) {
    if (session === collectedSession) return
    batteryCollector?.cancel()
    batteryPoller?.cancel()
    collectedSession = session
    batteryReading.value = null
    batteryOcvReading.value = OCVPreset.LI_ION.ocvArray
    batteryThresholdsSeeded = false
    if (session == null) {
        batteryNotifier.clearBatteryThresholds()
        return
    }
    batteryCollector = scope.launch {
        session.events.collect { event ->
            if (event is MeshEvent.BatteryReceived) {
                try {
                    publishBattery(event.battery)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A Device-row OCV read must not kill the live battery collector.
                }
            }
        }
    }
    // A companion radio reports its battery only when asked: `MeshEvent.BatteryReceived` answers an
    // explicit `getBattery`, so without this loop the readout stays empty and no threshold is ever
    // crossed. Mirrors iOS `BatteryMonitor`: one bootstrap read on connect, then a refresh every
    // [AppStateViewModel.BATTERY_REFRESH_INTERVAL]. A failed read leaves the previous value
    // alone and the loop retries.
    batteryPoller = scope.launch {
        while (true) {
            readDeviceBattery(logger::warning) { session.getBattery() }?.let { publishBattery(it) }
            delay(AppStateViewModel.BATTERY_REFRESH_INTERVAL)
        }
    }
}

internal suspend fun AppStateViewModel.publishBattery(battery: BatteryInfo, catchUpMissed: Boolean = false) {
    batteryReading.value = battery
    val ocvArray = connectedDeviceOcvArray(lastConnectionStore, database)
    batteryOcvReading.value = ocvArray
    val deviceName = lastConnectionStore.deviceName() ?: return
    val percentage = BatteryDisplay.percent(battery, ocvArray)
    val isPresent = BatteryDisplay.isBatteryPresent(battery)
    if (!batteryThresholdsSeeded) {
        batteryThresholdsSeeded = true
        batteryNotifier.initializeBatteryThreshold(deviceName, percentage, isPresent)
    } else if (catchUpMissed) {
        batteryNotifier.checkMissedBatteryThreshold(deviceName, percentage, isPresent)
    } else {
        batteryNotifier.checkBatteryThreshold(deviceName, percentage, isPresent)
    }
}

internal fun AppStateViewModel.rebindContactServiceEvents(services: ServiceContainer?) {
    if (services === collectedServices) return
    contactEventsCollector?.cancel()
    collectedServices = services
    if (services == null) return
    contactEventsCollector = scope.launch {
        services.contactService.isNodeStorageFull.collect { connectionUi.isNodeStorageFull = it }
    }
}

/**
 * Resets the connection-UI sub-flags the moment the live services drop, and re-evaluates the
 * disconnected pill on every rung change. Mirrors iOS `wireServicesIfConnected`, plus the rung
 * re-evaluation that layout needs here: an auto-reconnect holds the rung at CONNECTING with services
 * already gone, so the pill can only arm once that retry loop gives up or a connect attempt fails.
 */
internal suspend fun AppStateViewModel.rebindConnectionUi(state: ConnectionState, services: ServiceContainer?) {
    val servicesChanged = !connectionUiServicesSeen || services !== connectionUiServices
    connectionUiServicesSeen = true
    connectionUiServices = services
    if (services != null) {
        if (servicesChanged) connectionUi.hideDisconnectedPill()
        // The link is up. A bond-loss alert latched while the app was off screen must not sit
        // over a working radio — Remove-and-Try-Again would delete the saved device.
        connectionUi.clearPairingFailure()
        return
    }
    val rung = state.toDeviceConnectionState()
    val lastId = lastConnectionStore.deviceId()?.value
    val suppress = intentStore.restore().isUserDisconnected
    if (servicesChanged) {
        connectionUi.handleDisconnect(rung, lastId, suppress)
    } else {
        connectionUi.updateDisconnectedPillState(rung, lastId, suppress)
    }
}

/**
 * Surfaces the guided re-pair alert for an invalidated bond, once per episode. The reconnect
 * watchdog still ticks (iOS after `surfaceAuthenticationFailure`); the latch clears when the
 * state leaves [ConnectionState.BondLost] so a later episode presents again.
 */
internal fun AppStateViewModel.routeBondLoss(state: ConnectionState) {
    if (state !is ConnectionState.BondLost) {
        surfacedBondLossDeviceId = null
        return
    }
    if (surfacedBondLossDeviceId == state.deviceId) return
    surfacedBondLossDeviceId = state.deviceId
    // A user-initiated connect that threw has already classified this same episode, and it knows whether
    // the radio was saved; overwriting its alert would relabel a first pair's rejected PIN a dead bond.
    val alreadyRouted = connectionUi.connectionAlert.value
        .let { it is ConnectionAlert.Pairing && it.deviceId == state.deviceId }
    if (!alreadyRouted) {
        connectionUi.presentPairingFailure(
            PairingFailure.ConnectionFailed(state.deviceId, isAuthenticationFailure = true),
        )
    }
}

/**
 * The connected radio's active OCV curve resolved from its Device row, mirroring iOS
 * `device.activeOCVArray`. Falls back to the Li-Ion curve when the row or preset is absent.
 */
internal suspend fun connectedDeviceOcvArray(
    lastConnectionStore: LastConnectionStore,
    database: MeshDatabase,
): List<Int> = ocvArrayOrLiIon {
    val deviceId = lastConnectionStore.deviceId() ?: return@ocvArrayOrLiIon null
    val device = database.deviceDao().getById(deviceId) ?: return@ocvArrayOrLiIon null
    BatteryOcv.activeArray(device.ocvPreset, device.customOCVArrayString)
}

/**
 * Reads the Device-row OCV curve. A missing row or a persist fault falls back to Li-Ion so a
 * battery event cannot kill the live collector.
 */
internal suspend fun ocvArrayOrLiIon(read: suspend () -> List<Int>?): List<Int> =
    runExceptionFallback({ OCVPreset.LI_ION.ocvArray }) {
        read() ?: OCVPreset.LI_ION.ocvArray
    }
