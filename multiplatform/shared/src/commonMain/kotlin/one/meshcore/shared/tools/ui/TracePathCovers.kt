package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.tools.dismissResult
import one.meshcore.shared.tools.publishSavedPaths
import one.meshcore.shared.tools.reloadSaved

@Composable
internal fun TracePathCovers(
    ui: TracePathUiState,
    state: TracePathCoversState,
    dependencies: TracePathCoversDependencies,
    messages: TracePathCoversMessages,
) {
    val owner = ui.model.owner()
    val isCurrentOwner = { ui.model.owns(owner) }
    val builder = dependencies.builder
    val savedPathStore = dependencies.savedPathStore
    val radioId = dependencies.radioId
    val scope = dependencies.scope
    val showingListResults = ui.showingListResults
    val result = ui.result
    val mapMode = ui.mapMode
    val batchResults = ui.batchResults
    val isRunning = ui.isRunning
    val onDismissListResults = {
        ui.model.dismissResult()
        ui.showingListResults = false
    }
    val showingDistanceInfo = ui.showingDistanceInfo
    val onDismissDistance = { ui.showingDistanceInfo = false }
    val onShowDistanceInfo = { ui.showingDistanceInfo = true }
    val showHopPicker = ui.showHopPicker
    val onDismissHopPicker = {
        ui.showHopPicker = false
        ui.errorMessage = null
    }
    val saveNameDraft = ui.saveNameDraft
    val onSaveNameDraft: (String?) -> Unit = { ui.saveNameDraft = it }
    val batchEnabled = ui.batchEnabled
    val onSaveSucceeded = { ui.showingSaveSuccess = true }
    val onSaveFailed = { ui.showingSaveFailed = true }
    val showingSaveSuccess = ui.showingSaveSuccess
    val onDismissSaveSuccess = { ui.showingSaveSuccess = false }
    val showingSaveFailed = ui.showingSaveFailed
    val onDismissSaveFailed = { ui.showingSaveFailed = false }
    val errorMessage = ui.errorMessage
    val onDismissError = { ui.errorMessage = null }
    val showingSavedPaths = ui.showingSavedPaths
    val savedPaths = ui.savedPaths
    val onDismissSavedPaths = { ui.showingSavedPaths = false }
    val onSelectSavedPath: (SavedTracePathDto) -> Unit = { path ->
        builder.loadSavedPath(path.pathBytes, path.hashSize)
        ui.errorMessage = null
        ui.showingSavedPaths = false
    }
    val onStartRename: (SavedTracePathDto) -> Unit = { path ->
        ui.renamePath = path
        ui.renameDraft = path.name
    }
    val onAskDelete: (SavedTracePathDto) -> Unit = { ui.deleteConfirm = it }
    val detailPath = ui.detailPath
    val onDismissDetail = { ui.detailPath = null }
    val onLoadDetail: (SavedTracePathDto) -> Unit = { path ->
        builder.loadSavedPath(path.pathBytes, path.hashSize)
        ui.errorMessage = null
        ui.detailPath = null
    }
    val onDetailPath: (SavedTracePathDto?) -> Unit = { if (isCurrentOwner()) ui.detailPath = it }
    val renameDraft = ui.renameDraft
    val renamePath = ui.renamePath
    val onRenameDraft: (String?) -> Unit = { ui.renameDraft = it }
    val onRenamePath: (SavedTracePathDto?) -> Unit = { ui.renamePath = it }
    val onSavedPaths: (List<SavedTracePathDto>) -> Unit = { ui.model.publishSavedPaths(owner, it) }
    val onError: (String) -> Unit = { if (isCurrentOwner()) ui.errorMessage = it }
    val showingClearPath = ui.showingClearPath
    val onDismissClearPath = { ui.showingClearPath = false }
    val deleteConfirm = ui.deleteConfirm
    val onDismissDelete = { ui.deleteConfirm = null }
    TracePathResultCovers(
        showingListResults = showingListResults,
        resultMatchesPath = state.resultMatchesPath,
        result = result,
        mapMode = mapMode,
        batchResults = batchResults,
        isRunning = isRunning,
        onDismissListResults = onDismissListResults,
        showingDistanceInfo = showingDistanceInfo,
        onDismissDistance = onDismissDistance,
        onShowDistanceInfo = onShowDistanceInfo,
    )
    TracePathHopPickerCover(
        showHopPicker = showHopPicker,
        builder = builder,
        onDismiss = onDismissHopPicker,
    )
    TracePathSaveNameCover(
        saveNameDraft = saveNameDraft,
        onSaveNameDraft = onSaveNameDraft,
        savedPathStore = savedPathStore,
        radioId = { owner.radioId },
        isCurrentOwner = isCurrentOwner,
        scope = scope,
        hashSize = state.hashSize,
        batchEnabled = batchEnabled,
        batchResults = batchResults,
        result = result,
        haptics = dependencies.haptics,
        onReloadSaved = { ui.model.reloadSaved(owner) },
        onSaveSucceeded = onSaveSucceeded,
        onSaveFailed = onSaveFailed,
    )
    TracePathSaveOutcomeDialogs(
        showingSaveSuccess = showingSaveSuccess,
        onDismissSaveSuccess = onDismissSaveSuccess,
        showingSaveFailed = showingSaveFailed,
        saveFailedMessage = messages.saveFailed,
        onDismissSaveFailed = onDismissSaveFailed,
        showingClearPath = showingClearPath,
        onDismissClearPath = onDismissClearPath,
        onResetPath = dependencies.resetPath,
    )
    TracePathErrorCover(
        errorMessage = errorMessage,
        loadFailedMessage = messages.loadFailed,
        renameFailedMessage = messages.renameFailed,
        deleteFailedMessage = messages.deleteFailed,
        onDismiss = onDismissError,
    )
    TracePathSavedPathsCover(
        showingSavedPaths = showingSavedPaths,
        savedPaths = savedPaths,
        nowSeconds = state.nowSeconds,
        onDismissSavedPaths = onDismissSavedPaths,
        onSelectSavedPath = onSelectSavedPath,
        onStartRename = onStartRename,
        onAskDelete = onAskDelete,
        detailPath = detailPath,
        onDismissDetail = onDismissDetail,
        onLoadDetail = onLoadDetail,
    )
    TracePathRenameCover(
        renameDraft = renameDraft,
        renamePath = renamePath,
        onRenameDraft = onRenameDraft,
        onRenamePath = onRenamePath,
        savedPathStore = savedPathStore,
        scope = scope,
        detailPath = detailPath,
        onDetailPath = onDetailPath,
        onSavedPaths = onSavedPaths,
        onError = onError,
        loadFailedMessage = messages.loadFailed,
        renameFailedMessage = messages.renameFailed,
        radioId = { owner.radioId },
        isCurrentOwner = isCurrentOwner,
    )
    TracePathDeleteCover(
        deleteConfirm = deleteConfirm,
        onDismissDelete = onDismissDelete,
        savedPathStore = savedPathStore,
        scope = scope,
        radioId = { owner.radioId },
        isCurrentOwner = isCurrentOwner,
        onSavedPaths = onSavedPaths,
        onError = onError,
        onDetailPath = onDetailPath,
        loadFailedMessage = messages.loadFailed,
        deleteFailedMessage = messages.deleteFailed,
    )
}
