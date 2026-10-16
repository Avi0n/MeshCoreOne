package one.meshcore.shared.settings.ui

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.settings.NodeConfigDeviceDiff
import one.meshcore.shared.settings.NodeConfigService

/** A missing comparison does not prevent file preview; cancelling its read still stops the import. */
internal suspend fun readConfigImportDeviceDiff(read: suspend () -> NodeConfigDeviceDiff?): NodeConfigDeviceDiff? =
    runExceptionFallback({ null }, read)

internal suspend fun readCurrentConfigImportDeviceDiff(
    serviceProvider: () -> NodeConfigService?,
    radioIdProvider: () -> Uuid?,
): NodeConfigDeviceDiff? {
    val service = serviceProvider() ?: return null
    val radioId = radioIdProvider() ?: return null
    val diff = service.loadDeviceStateForDiff()
    val isCurrent = { serviceProvider() === service && radioIdProvider() == radioId }
    return diff.takeIf { isCurrent() }?.copy(isCurrent = isCurrent)
}
