package one.meshcore.shared.settings.ui

import kotlinx.coroutines.Job
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigDeviceDiff

/** Live reads and writes for mounted previews and retained configuration writes. */
internal data class NodeConfigImportBindings(
    val isApplying: () -> Boolean,
    val claimApply: () -> Boolean,
    val importJob: () -> Job?,
    val isConfirmationCurrent: () -> Boolean,
    val setConfirmationCurrent: (() -> Boolean) -> Unit,
    val isParsing: () -> Boolean,
    val isPreparingConfirmation: () -> Boolean,
    val setApplying: (Boolean) -> Unit,
    val setParsing: (Boolean) -> Unit,
    val setPreparingConfirmation: (Boolean) -> Unit,
    val setErrorMessage: (String?) -> Unit,
    val setImportComplete: (Boolean) -> Unit,
    val setDidApplyAnyWrite: (Boolean) -> Unit,
    val setApplyProgress: (Float) -> Unit,
    val setApplyStepDescription: (String) -> Unit,
    val setImportJob: (Job?) -> Unit,
    val setShowConfirmation: (Boolean) -> Unit,
    val setChannelsWouldOverwrite: (Boolean) -> Unit,
    val setImportedConfig: (MeshCoreNodeConfig?) -> Unit,
    val setSections: (ConfigSections) -> Unit,
    val setDeviceDiff: (NodeConfigDeviceDiff?) -> Unit,
    val resetToFileSelection: () -> Unit,
)

internal fun NodeConfigImportState.bindings(workspace: NodeConfigImportWorkspace?): NodeConfigImportBindings =
    NodeConfigImportBindings(
        isApplying = { this.isApplying },
        claimApply = { workspace?.claim(this) ?: !this.isApplying },
        importJob = { this.importJob },
        isConfirmationCurrent = { this.confirmationIsCurrent() },
        setConfirmationCurrent = { this.confirmationIsCurrent = it },
        isParsing = { this.isParsing },
        isPreparingConfirmation = { this.isPreparingConfirmation },
        setApplying = { this.isApplying = it },
        setParsing = { this.isParsing = it },
        setPreparingConfirmation = { this.isPreparingConfirmation = it },
        setErrorMessage = { this.errorMessage = it },
        setImportComplete = { this.importComplete = it },
        setDidApplyAnyWrite = { this.didApplyAnyWrite = it },
        setApplyProgress = { this.applyProgress = it },
        setApplyStepDescription = { this.applyStepDescription = it },
        setImportJob = { this.importJob = it },
        setShowConfirmation = { this.showConfirmation = it },
        setChannelsWouldOverwrite = { this.channelsWouldOverwrite = it },
        setImportedConfig = { this.importedConfig = it },
        setSections = { this.sections = it },
        setDeviceDiff = { this.deviceDiff = it },
        resetToFileSelection = this::resetToFileSelection,
    )
