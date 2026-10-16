package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.transport.BluetoothUnauthorizedException

internal fun ConnectionManager.requireBleConnectAuthorization(deviceId: String) {
    if (bluetoothConnectAuthorized()) return
    val idle = link == null && session == null && services == null
    val targetMatches = currentDeviceId == null || currentDeviceId == deviceId
    if (idle && targetMatches && currentTransportType != TransportType.WIFI) {
        wantsConnection = true
        currentDeviceId = deviceId
        currentTransportType = TransportType.BLUETOOTH
        mutableState.value = ConnectionState.Disconnected
    }
    throw BluetoothUnauthorizedException()
}

internal suspend fun ConnectionManager.parkUnauthorizedBle(deviceId: String, generation: Long) {
    if (!ownsUnauthorizedBle(deviceId, generation)) return
    val retiredGeneration = tearDownCurrent(stopSession = false)
    if (ownsUnauthorizedBle(deviceId, retiredGeneration)) {
        mutableState.value = ConnectionState.Disconnected
    }
}

internal suspend fun ConnectionManager.throwUnauthorizedBleFailure(
    deviceId: String,
    generation: Long,
    error: Throwable,
): Nothing {
    parkUnauthorizedBle(deviceId, generation)
    throw error
}

private fun ConnectionManager.ownsUnauthorizedBle(deviceId: String, generation: Long): Boolean =
    ownsConnectAttempt(generation) &&
        currentDeviceId == deviceId &&
        currentTransportType == TransportType.BLUETOOTH &&
        wantsConnection

internal fun isBluetoothUnauthorized(error: Throwable): Boolean = error is BluetoothUnauthorizedException ||
    (error is MeshBridgeException && error.code == BridgeErrorCode.BLUETOOTH_UNAUTHORIZED)
