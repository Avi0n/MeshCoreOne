package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.settings_config_import_cancelled
import one.meshcore.shared.resources.settings_config_import_cancelled_partial
import one.meshcore.shared.resources.settings_config_import_failed_partial
import one.meshcore.shared.resources.settings_config_import_step_channel
import one.meshcore.shared.resources.settings_config_import_step_contact
import one.meshcore.shared.resources.settings_config_import_step_node_name
import one.meshcore.shared.resources.settings_config_import_step_other_parameters
import one.meshcore.shared.resources.settings_config_import_step_position
import one.meshcore.shared.resources.settings_config_import_step_private_key
import one.meshcore.shared.resources.settings_config_import_step_radio_parameters
import one.meshcore.shared.resources.settings_config_import_step_tx_power
import one.meshcore.shared.resources.settings_config_import_this_device
import one.meshcore.shared.settings.ImportStep
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.ui.rememberDetachedScope
import org.jetbrains.compose.resources.stringResource

private const val SUCCESS_RESET_MS = 1_500L

/**
 * Settings → Advanced → Import Configuration: pick JSON, preview state.sections, confirm, apply with progress.
 * Mirrors iOS `NodeConfigImportView` / ViewModel (preview-before-confirm, partial-write messages).
 */
@Composable
fun NodeConfigImportScreen(
    nodeConfigService: () -> NodeConfigService?,
    radioId: () -> Uuid?,
    deviceNodeName: () -> String?,
    pickJsonFile: suspend () -> String?,
    onImportApplied: suspend () -> Unit = {},
    workspace: NodeConfigImportWorkspace? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val importScope = workspace?.scope ?: rememberDetachedScope()
    val localState = remember { NodeConfigImportState() }
    val state = workspace?.confirmedState ?: workspace?.pendingState ?: localState
    val configErrorText = rememberNodeConfigErrorText()
    val unmappedConfigError = stringResource(Res.string.contacts_common_error_occurred)
    val service = nodeConfigService()
    val thisDeviceLabel = stringResource(Res.string.settings_config_import_this_device)
    val cancelledMsg = stringResource(Res.string.settings_config_import_cancelled)
    val cancelledPartialMsg = stringResource(Res.string.settings_config_import_cancelled_partial)
    val failedPartialTemplate = stringResource(Res.string.settings_config_import_failed_partial)
    val stepLabels = rememberImportStepLabels()

    ConfigImportPresentationEffect(workspace, state)

    val bindings = state.bindings(workspace)

    val pickRequest = ConfigImportPickRequest(
        parsing = state.isParsing,
        pickJsonFile = pickJsonFile,
        loadDeviceDiff = { readCurrentConfigImportDeviceDiff(nodeConfigService, radioId) },
        claim = { workspace?.claimPick(state) ?: !state.isParsing },
        isCurrent = { workspace?.isPickCurrent(state) ?: true },
        installJob = { workspace?.installPickJob(state, it) },
        configErrorText = configErrorText,
        unmappedConfigError = unmappedConfigError,
        bindings = bindings,
    )

    LaunchedEffect(workspace, state) {
        launchRecoveredConfigImportPick(workspace, state, pickRequest)
    }

    if (state.showConfirmation) {
        ConfigImportConfirmDialog(
            sections = state.sections,
            channelsWouldOverwrite = state.channelsWouldOverwrite,
            deviceNodeName = deviceNodeName,
            thisDeviceLabel = thisDeviceLabel,
            importedConfig = state.importedConfig,
            nodeConfigService = nodeConfigService,
            radioId = radioId,
            importScope = importScope,
            describeStep = stepLabels::describe,
            onImportApplied = onImportApplied,
            configErrorText = configErrorText,
            unmappedConfigError = unmappedConfigError,
            cancelledMsg = cancelledMsg,
            cancelledPartialMsg = cancelledPartialMsg,
            failedPartialTemplate = failedPartialTemplate,
            successResetMs = SUCCESS_RESET_MS,
            bindings = bindings,
        )
    }

    NodeConfigImportContent(
        state = state,
        serviceAvailable = service != null,
        nodeConfigService = nodeConfigService,
        radioId = radioId,
        deviceNodeName = deviceNodeName,
        thisDeviceLabel = thisDeviceLabel,
        scope = scope,
        configErrorText = configErrorText,
        unmappedConfigError = unmappedConfigError,
        bindings = bindings,
        onSelectFile = { launchConfigImportPick(workspace?.scope ?: scope, pickRequest) },
        modifier = modifier,
    )
}

@Composable
private fun ConfigImportPresentationEffect(workspace: NodeConfigImportWorkspace?, state: NodeConfigImportState) {
    DisposableEffect(workspace, state) {
        val presentation = workspace?.acquirePresentation(state)
        onDispose { presentation?.let { workspace?.release(it) } }
    }
}

/** Pre-loaded L10n templates so progress callbacks (non-composable) can format step lines. */
private data class ImportStepLabels(
    val position: String,
    val otherParameters: String,
    val privateKey: String,
    val nodeName: String,
    val radioParameters: String,
    val txPower: String,
    val channelTemplate: String,
    val contactTemplate: String,
) {
    fun describe(step: ImportStep): String = when (step) {
        ImportStep.Position -> position
        ImportStep.OtherParameters -> otherParameters
        ImportStep.PrivateKey -> privateKey
        ImportStep.NodeName -> nodeName
        ImportStep.RadioParameters -> radioParameters
        ImportStep.TxPower -> txPower
        is ImportStep.Channel ->
            one.meshcore.shared.ui.formatTemplate(channelTemplate, listOf(step.name))
        is ImportStep.Contact ->
            one.meshcore.shared.ui.formatTemplate(contactTemplate, listOf(step.name))
    }
}

@Composable
private fun rememberImportStepLabels(): ImportStepLabels = ImportStepLabels(
    position = stringResource(Res.string.settings_config_import_step_position),
    otherParameters = stringResource(Res.string.settings_config_import_step_other_parameters),
    privateKey = stringResource(Res.string.settings_config_import_step_private_key),
    nodeName = stringResource(Res.string.settings_config_import_step_node_name),
    radioParameters = stringResource(Res.string.settings_config_import_step_radio_parameters),
    txPower = stringResource(Res.string.settings_config_import_step_tx_power),
    channelTemplate = stringResource(Res.string.settings_config_import_step_channel),
    contactTemplate = stringResource(Res.string.settings_config_import_step_contact),
)
