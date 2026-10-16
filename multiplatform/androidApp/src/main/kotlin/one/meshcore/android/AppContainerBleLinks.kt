package one.meshcore.android

import one.meshcore.shared.connection.shouldAdoptSystemConnectedGatt
import one.meshcore.shared.transport.BleTransport
import one.meshcore.shared.transport.KableBleTransport
import one.meshcore.shared.transport.systemConnectedBleHandles

internal fun AppContainer.buildBleLink(
    deviceId: String,
    reconnect: Boolean,
    bluetoothConnectAuthorized: () -> Boolean,
) = KableBleTransport(
    appScope,
    deviceId = deviceId,
    options = KableBleTransport.Options(
        writePacingMillis = bleWritePacingMillis(deviceId),
        discoveryWindow = if (reconnect) {
            BleTransport.AUTO_RECONNECT_DISCOVERY_WINDOW
        } else {
            BleTransport.DEFAULT_DISCOVERY_WINDOW
        },
        autoConnect = reconnect && !shouldAdoptSystemConnectedGatt(deviceId, appContext.systemConnectedBleHandles()),
        bluetoothConnectAuthorized = bluetoothConnectAuthorized,
    ),
)
