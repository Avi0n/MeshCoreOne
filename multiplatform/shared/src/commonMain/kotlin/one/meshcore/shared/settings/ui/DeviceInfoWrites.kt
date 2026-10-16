package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class NodeNameSave(
    val name: String,
    val inFlight: Boolean,
    val onSave: suspend (String) -> Unit,
    val onSaving: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchNodeNameSave(scope: CoroutineScope, request: NodeNameSave) {
    if (request.name.isEmpty() || request.inFlight) return
    request.onSaving(true)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = request.settingsWriteReport { launchNodeNameSave(scope, request) },
        onFinished = { request.onSaving(false) },
    ) {
        request.onSave(request.name)
    }
}

/** Callbacks shared by a private-key import and a generated-identity replace. */
internal interface IdentityWriteRequest {
    val featureDisabledMessage: String
    val rejectedMessage: String
    val sessionText: (Throwable) -> String
    val onError: (String?) -> Unit
    val onBusyChange: (Boolean) -> Unit
    val onImportingChange: (Boolean) -> Unit
    val onCleared: () -> Unit
    val onHapticSuccess: () -> Unit
    val onSuccess: () -> Unit
}

internal data class PrivateKeyImport(
    val key: ByteArray,
    override val featureDisabledMessage: String,
    override val rejectedMessage: String,
    override val sessionText: (Throwable) -> String,
    override val onError: (String?) -> Unit,
    override val onBusyChange: (Boolean) -> Unit,
    override val onImportingChange: (Boolean) -> Unit,
    val onImport: suspend (ByteArray) -> Unit,
    override val onCleared: () -> Unit,
    override val onHapticSuccess: () -> Unit,
    override val onSuccess: () -> Unit,
) : IdentityWriteRequest

internal fun launchPrivateKeyImport(scope: CoroutineScope, request: PrivateKeyImport) {
    launchIdentityWrite(scope, request) { request.onImport(request.key) }
}

/** Marks the write busy, runs [write], then clears busy. A cancel still propagates. */
@Suppress("TooGenericExceptionCaught")
internal fun launchIdentityWrite(scope: CoroutineScope, request: IdentityWriteRequest, write: suspend () -> Unit) {
    request.onImportingChange(true)
    request.onBusyChange(true)
    request.onError(null)
    scope.launch {
        try {
            write()
            request.onCleared()
            request.onHapticSuccess()
            request.onSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.onError(
                identityWriteErrorMessage(
                    error,
                    request.featureDisabledMessage,
                    request.rejectedMessage,
                    request.sessionText,
                ),
            )
        } finally {
            request.onImportingChange(false)
            request.onBusyChange(false)
        }
    }
}
