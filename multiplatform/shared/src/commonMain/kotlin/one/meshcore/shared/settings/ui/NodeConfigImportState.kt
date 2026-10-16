package one.meshcore.shared.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.NodeConfigDeviceDiff

internal class NodeConfigImportState {
    var importedConfig by mutableStateOf<MeshCoreNodeConfig?>(null)
    var sections by mutableStateOf(ConfigSections())
    var deviceDiff by mutableStateOf<NodeConfigDeviceDiff?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var isParsing by mutableStateOf(false)
    var isApplying by mutableStateOf(false)
    var isPreparingConfirmation by mutableStateOf(false)
    var showConfirmation by mutableStateOf(false)
    var channelsWouldOverwrite by mutableStateOf(false)
    var applyProgress by mutableStateOf(0f)
    var applyStepDescription by mutableStateOf("")
    var importComplete by mutableStateOf(false)
    var didApplyAnyWrite by mutableStateOf(false)
    var importJob: Job? = null
    var confirmationIsCurrent: () -> Boolean = { false }

    val currentDeviceDiff: NodeConfigDeviceDiff? get() = deviceDiff?.takeIf { it.isCurrent() }

    val hasFileContent: Boolean get() = importedConfig != null || isParsing || errorMessage != null

    fun showsFilePicker(serviceAvailable: Boolean): Boolean = serviceAvailable || isParsing

    fun resetToFileSelection() {
        importedConfig = null
        errorMessage = null
        isParsing = false
        sections = ConfigSections()
        deviceDiff = null
        applyProgress = 0f
        applyStepDescription = ""
        importComplete = false
        isApplying = false
        isPreparingConfirmation = false
        showConfirmation = false
        channelsWouldOverwrite = false
        didApplyAnyWrite = false
        confirmationIsCurrent = { false }
        importJob = null
    }
}
