package one.meshcore.android

import one.meshcore.shared.connection.FAILED_PERSIST_LAST_CONNECTION
import one.meshcore.shared.connection.applyLastConnectionPersist
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.toMeshError
import one.meshcore.shared.settings.PersistentLogger

internal fun AppContainer.buildRequestTelemetry(): suspend (ByteArray) -> TelemetryResponse = { publicKey ->
    val session = connectionManager.services?.session
        ?: throw one.meshcore.shared.remotenode.MeshError.NotConnected
    one.meshcore.shared.remotenode.performWithPathResetOnTimeout(
        publicKey = publicKey,
        resetPath = session::resetPath,
        operationName = "telemetry",
        logger = PersistentLogger(appScope, category = "BinaryProtocol"),
        operation = {
            try {
                session.requestTelemetry(publicKey)
            } catch (e: MeshBridgeException) {
                throw e.toMeshError()
            }
        },
    )
}

internal fun AppContainer.buildImportPrivateKey(): suspend (ByteArray) -> Unit = { key ->
    val services = connectionManager.services
        ?: error("No radio connected")
    services.session.importPrivateKey(key)
    // Keep Device.publicKey aligned with the radio after rotation; radioID partition preserved
    // (or adopted from a matching ghost). Mirrors NodeConfigService.onPostIdentityImport.
    val deviceId = lastConnectionStore.deviceId()
    val adopted = services.reconcileAfterIdentityImport(currentDeviceId = deviceId)
    val handle = lastConnectionStore.handle()
    val name = lastConnectionStore.deviceName().orEmpty()
    if (deviceId != null && handle != null) {
        applyLastConnectionPersist(
            persist = { lastConnectionStore.persist(deviceId, adopted, name, handle) },
            logger = connectionLogger,
            failLine = FAILED_PERSIST_LAST_CONNECTION,
        )
    }
}
