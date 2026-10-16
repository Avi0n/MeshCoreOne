package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.runReportingException
import one.meshcore.shared.settings.ImportPreview
import one.meshcore.shared.settings.ImportProgress
import one.meshcore.shared.settings.ImportStep
import one.meshcore.shared.settings.NodeConfigDeviceDiff
import one.meshcore.shared.settings.NodeConfigJson
import one.meshcore.shared.settings.autoSelectSections
import one.meshcore.shared.ui.formatTemplate

internal data class ConfigImportApplyRequest(
    val importConfig: suspend (onProgress: (ImportProgress) -> Unit) -> Unit,
    val onImportApplied: suspend () -> Unit,
    val describeStep: (ImportStep) -> String,
    val configErrorText: (Throwable) -> String?,
    val unmappedConfigError: String,
    val cancelledMsg: String,
    val cancelledPartialMsg: String,
    val failedPartialTemplate: String,
    val successResetMs: Long,
    val bindings: NodeConfigImportBindings,
)

@Suppress("TooGenericExceptionCaught")
internal fun launchConfigImportApply(scope: CoroutineScope, request: ConfigImportApplyRequest) {
    val bindings = request.bindings
    if (!bindings.claimApply()) return
    bindings.setConfirmationCurrent { false }
    bindings.setApplying(true)
    bindings.setApplyProgress(0f)
    bindings.setErrorMessage(null)
    bindings.setImportComplete(false)
    bindings.setDidApplyAnyWrite(false)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        var wroteAny = false
        try {
            request.importConfig { progress ->
                wroteAny = true
                bindings.setDidApplyAnyWrite(true)
                bindings.setApplyProgress(
                    progress.current.toFloat() / progress.total.coerceAtLeast(1).toFloat(),
                )
                bindings.setApplyStepDescription(request.describeStep(progress.step))
            }
            // Refresh after import so Settings and backup see the radio's new
            // name, coords, and other params.
            request.onImportApplied()
            bindings.setApplying(false)
            bindings.setImportComplete(true)
            delay(request.successResetMs)
            if (bindings.importJob() === currentCoroutineContext()[Job] && !bindings.isApplying()) {
                bindings.resetToFileSelection()
            }
        } catch (cancelled: CancellationException) {
            bindings.setApplying(false)
            bindings.setErrorMessage(
                if (wroteAny) request.cancelledPartialMsg else request.cancelledMsg,
            )
            throw cancelled
        } catch (error: Exception) {
            bindings.setApplying(false)
            // A planner rejection carries developer English; the shipped message
            // says the same thing in the user's language and names the field.
            val base = nodeConfigUserFacingMessage(
                error,
                request.configErrorText,
                request.unmappedConfigError,
            )
            bindings.setErrorMessage(
                if (wroteAny) {
                    formatTemplate(request.failedPartialTemplate, listOf(base))
                } else {
                    base
                },
            )
        }
    }
    bindings.setImportJob(job)
    job.start()
}

internal data class ConfigImportPickRequest(
    val parsing: Boolean,
    val pickJsonFile: suspend () -> String?,
    val loadDeviceDiff: suspend () -> NodeConfigDeviceDiff?,
    val claim: () -> Boolean,
    val isCurrent: () -> Boolean,
    val installJob: (Job) -> Unit,
    val configErrorText: (Throwable) -> String?,
    val unmappedConfigError: String,
    val bindings: NodeConfigImportBindings,
)

internal fun launchRecoveredConfigImportPick(
    workspace: NodeConfigImportWorkspace?,
    state: NodeConfigImportState,
    request: ConfigImportPickRequest,
) {
    val owner = workspace ?: return
    val reader = owner.takeRecoveredReader(state) ?: return
    launchConfigImportPick(
        owner.scope,
        request.copy(
            parsing = false,
            pickJsonFile = reader,
            claim = { owner.isPickCurrent(state) },
        ),
    )
}

internal fun launchConfigImportPick(scope: CoroutineScope, request: ConfigImportPickRequest) {
    if (request.parsing || !request.claim()) return
    val bindings = request.bindings
    bindings.setParsing(true)
    bindings.setErrorMessage(null)
    val job = scope.launch(start = CoroutineStart.LAZY) {
        try {
            runReportingException<Unit>(
                onFailure = { error ->
                    if (request.isCurrent()) {
                        bindings.setErrorMessage(
                            nodeConfigUserFacingMessage(error, request.configErrorText, request.unmappedConfigError),
                        )
                    }
                },
            ) {
                val text = request.pickJsonFile()
                currentCoroutineContext().ensureActive()
                if (!request.isCurrent()) return@runReportingException
                if (text == null) {
                    bindings.setParsing(false)
                } else {
                    val decoded = NodeConfigJson.decode(text)
                    bindings.setImportedConfig(decoded)
                    bindings.setSections(autoSelectSections(decoded))
                    bindings.setErrorMessage(null)
                    val diff = readConfigImportDeviceDiff(request.loadDeviceDiff)
                    currentCoroutineContext().ensureActive()
                    if (request.isCurrent() && diff != null) bindings.setDeviceDiff(diff)
                }
            }
        } finally {
            if (request.isCurrent()) bindings.setParsing(false)
        }
    }
    request.installJob(job)
    job.start()
}

internal data class ConfigImportPreviewRequest(
    val inFlight: Boolean,
    val preview: suspend () -> ImportPreview,
    val configErrorText: (Throwable) -> String?,
    val unmappedConfigError: String,
    val bindings: NodeConfigImportBindings,
)

internal fun launchConfigImportPreview(scope: CoroutineScope, request: ConfigImportPreviewRequest) {
    if (request.inFlight) return
    val bindings = request.bindings
    bindings.setPreparingConfirmation(true)
    bindings.setErrorMessage(null)
    scope.launch {
        try {
            runReportingException<Unit>(
                onFailure = { error ->
                    // Preview runs the planner, so this is where most rejections are raised.
                    bindings.setErrorMessage(
                        nodeConfigUserFacingMessage(error, request.configErrorText, request.unmappedConfigError),
                    )
                },
            ) {
                val preview = request.preview()
                bindings.setChannelsWouldOverwrite(preview.channelsOverwriteExisting)
                bindings.setShowConfirmation(true)
            }
        } finally {
            bindings.setPreparingConfirmation(false)
        }
    }
}
