package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class NodeConfigExportWrite(
    val inFlight: Boolean,
    val hasSelection: Boolean,
    val onExport: suspend () -> Unit,
    val onExporting: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
    val bridgeErrorText: (Throwable) -> String?,
    val exportFailedMessage: String,
)

@Suppress("TooGenericExceptionCaught")
internal fun launchNodeConfigExport(scope: CoroutineScope, request: NodeConfigExportWrite) {
    if (request.inFlight || !request.hasSelection) return
    request.onExporting(true)
    request.onError(null)
    scope.launch {
        try {
            request.onExport()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.onError(
                nodeConfigUserFacingMessage(
                    error,
                    request.bridgeErrorText,
                    request.exportFailedMessage,
                ),
            )
        } finally {
            request.onExporting(false)
        }
    }
}
