package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** How a failed radio-settings command is shown: retry when the link is transient, otherwise [onError]. */
internal data class SettingsWriteReport(
    val retryAlert: RetryAlertState,
    val onError: (String?) -> Unit,
    val settingsErrorText: (Throwable) -> String,
    val onRetry: () -> Unit,
    val onMaxRetriesExceeded: () -> Unit,
)

/** Retry and error callbacks that every radio-settings write already carries. */
internal interface SettingsWriteCallbacks {
    val retryAlert: RetryAlertState
    val onError: (String?) -> Unit
    val settingsErrorText: (Throwable) -> String
    val onMaxRetriesExceeded: () -> Unit
}

internal fun SettingsWriteCallbacks.settingsWriteReport(onRetry: () -> Unit): SettingsWriteReport = SettingsWriteReport(
    retryAlert = retryAlert,
    onError = onError,
    settingsErrorText = settingsErrorText,
    onRetry = onRetry,
    onMaxRetriesExceeded = onMaxRetriesExceeded,
)

/**
 * Runs one radio-settings command. Cancellation stops the caller. A transient failure offers
 * retry; every other failure is reported through [SettingsWriteReport.onError].
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runRetryableSettingsWrite(report: SettingsWriteReport, block: suspend () -> Unit) {
    try {
        block()
        report.retryAlert.reset()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error.isRetryableSettingsError()) {
            report.retryAlert.show(
                message = report.settingsErrorText(error),
                onRetry = report.onRetry,
                onMaxRetriesExceeded = report.onMaxRetriesExceeded,
            )
        } else {
            report.onError(report.settingsErrorText(error))
        }
    }
}

/**
 * Marks a settings write busy, clears the previous error, and sets applied only after the command
 * returns. The busy flag clears when the launch finishes, including cancellation.
 */
internal fun CoroutineScope.launchAppliedSettingsWrite(
    callbacks: SettingsWriteCallbacks,
    onBusy: (Boolean) -> Unit,
    onApplied: (Boolean) -> Unit,
    retry: () -> Unit,
    write: suspend () -> Unit,
) {
    onBusy(true)
    callbacks.onError(null)
    onApplied(false)
    launchRetryableSettingsWrite(
        report = callbacks.settingsWriteReport(retry),
        onFinished = { onBusy(false) },
    ) {
        write()
        onApplied(true)
    }
}

/** Starts a settings write and always runs [onFinished], including when the command is cancelled. */
internal fun CoroutineScope.launchRetryableSettingsWrite(
    report: SettingsWriteReport,
    onFinished: () -> Unit,
    block: suspend () -> Unit,
) {
    launch {
        try {
            runRetryableSettingsWrite(report, block)
        } finally {
            onFinished()
        }
    }
}
