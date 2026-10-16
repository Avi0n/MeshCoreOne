package one.meshcore.shared.tools.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.tools.TracePathViewModel
import one.meshcore.shared.tools.setAutoReturn
import one.meshcore.shared.tools.setBatchCount
import one.meshcore.shared.tools.setBatchEnabled
import one.meshcore.shared.tools.setError
import one.meshcore.shared.tools.setMapFilter
import one.meshcore.shared.tools.setMapMode

/** Mount-local dialogs with a write-through adapter to the retained business state. */
internal class TracePathUiState(val model: TracePathViewModel, private val state: State<TracePathViewModel.State>) {
    var autoReturn: Boolean
        get() = state.value.autoReturn
        set(value) = model.setAutoReturn(value)
    var batchEnabled: Boolean
        get() = state.value.batchEnabled
        set(value) = model.setBatchEnabled(value)
    var batchCount: Int
        get() = state.value.batchCount
        set(value) = model.setBatchCount(value)
    val isRunning get() = state.value.isRunning
    val batchProgress get() = state.value.batchProgress
    val result get() = state.value.result
    val batchResults get() = state.value.batchResults
    var errorMessage: String?
        get() = state.value.errorMessage
        set(value) = model.setError(value)
    val savedPaths get() = state.value.savedPaths
    var mapMode: Boolean
        get() = state.value.mapMode
        set(value) = model.setMapMode(value)
    var mapFilter: MapFilterState
        get() = state.value.mapFilter
        set(value) = model.setMapFilter(value)
    var showHopPicker by mutableStateOf(false)
    var saveNameDraft by mutableStateOf<String?>(null)
    var showingSaveSuccess by mutableStateOf(false)
    var showingSaveFailed by mutableStateOf(false)
    var showingSavedPaths by mutableStateOf(false)
    var showingDistanceInfo by mutableStateOf(false)
    var detailPath by mutableStateOf<SavedTracePathDto?>(null)
    var renamePath by mutableStateOf<SavedTracePathDto?>(null)
    var renameDraft by mutableStateOf<String?>(null)
    var deleteConfirm by mutableStateOf<SavedTracePathDto?>(null)
    var showingClearPath by mutableStateOf(false)
    var showingListResults by mutableStateOf(false)
    val deviceHashMode get() = state.value.deviceHashMode
    var showJumpToPath by mutableStateOf(true)
}
