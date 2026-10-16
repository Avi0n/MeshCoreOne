package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.settings.PersistentLogger

internal class BleDisconnectCleanup(private val peripheral: BlePeripheral, private val logger: PersistentLogger) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun disconnect(primaryFailure: Throwable? = null) {
        try {
            peripheral.disconnect()
        } catch (cancelled: CancellationException) {
            if (primaryFailure == null) throw cancelled
            if (primaryFailure !== cancelled) primaryFailure.addSuppressed(cancelled)
        } catch (error: Exception) {
            logger.warning("[BLE] Disconnect cleanup failed: ${error::class.simpleName}")
            if (primaryFailure !== error) primaryFailure?.addSuppressed(error)
        }
    }
}
