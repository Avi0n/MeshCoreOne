package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.ui.AddHopPickerScreen
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_results_title
import one.meshcore.shared.resources.contacts_trace_clear_path
import one.meshcore.shared.resources.contacts_trace_clear_path_message
import one.meshcore.shared.resources.contacts_trace_failed
import one.meshcore.shared.resources.contacts_trace_map_save_failed_title
import one.meshcore.shared.resources.contacts_trace_map_saved_message
import one.meshcore.shared.resources.contacts_trace_map_saved_title
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.isDistanceUsingDeviceLocationFallback
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.modalSheetExpandedValues
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracePathResultCovers(
    showingListResults: Boolean,
    resultMatchesPath: Boolean,
    result: TraceResult?,
    mapMode: Boolean,
    batchResults: List<TraceResult>,
    isRunning: Boolean,
    onDismissListResults: () -> Unit,
    showingDistanceInfo: Boolean,
    onDismissDistance: () -> Unit,
    onShowDistanceInfo: () -> Unit,
) {
    val hasListResultForPath = showingListResults && resultMatchesPath
    if (hasListResultForPath && result?.success == true && !mapMode) {
        ModalBottomSheet(
            onDismissRequest = onDismissListResults,
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = modalSheetExpandedValues,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = PreferenceHubSectionGap),
            ) {
                Text(
                    text = stringResource(Res.string.contacts_results_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(
                        horizontal = PreferenceHubHorizontalPadding,
                        vertical = PreferenceHubTopPadding,
                    ),
                )
                TraceResultSection(
                    result = result.takeIf { resultMatchesPath },
                    batchResults = batchResults,
                    isBatchInProgress = isRunning,
                    onShowDistanceInfo = onShowDistanceInfo,
                    wrapInCard = false,
                )
            }
        }
    }
    result?.takeIf { showingDistanceInfo && resultMatchesPath }?.let { current ->
        DistanceInfoDialog(
            result = current,
            isFallback = isDistanceUsingDeviceLocationFallback(current.hops),
            onDismiss = onDismissDistance,
        )
    }
}

@Composable
internal fun TracePathHopPickerCover(showHopPicker: Boolean, builder: TracePathBuilder, onDismiss: () -> Unit) {
    if (showHopPicker) {
        // The shared Add-Hop picker. The trace builder is an uncapped source, same as the contact editor.
        FullScreenOverlay(onDismiss = onDismiss) {
            AddHopPickerScreen(
                source = builder,
                onBack = onDismiss,
            )
        }
    }
}

@Composable
internal fun TracePathSaveNameCover(
    saveNameDraft: String?,
    onSaveNameDraft: (String?) -> Unit,
    savedPathStore: SavedTracePathStore?,
    radioId: () -> Uuid?,
    isCurrentOwner: () -> Boolean,
    scope: CoroutineScope,
    hashSize: Int,
    batchEnabled: Boolean,
    batchResults: List<TraceResult>,
    result: TraceResult?,
    haptics: AppHaptics,
    onReloadSaved: suspend () -> Unit,
    onSaveSucceeded: () -> Unit,
    onSaveFailed: () -> Unit,
) {
    saveNameDraft?.let { draft ->
        TracePathSaveNameDialog(
            name = draft,
            onNameChange = { onSaveNameDraft(it) },
            onDismiss = { onSaveNameDraft(null) },
            onConfirm = {
                val store = savedPathStore
                val radio = radioId()
                if (store != null && radio != null && isCurrentOwner()) {
                    scope.launch {
                        // A run with nothing savable and a store that threw must not look like a successful save.
                        val stored = runSavedTraceStore(false) {
                            persistCompletedTracePath(
                                store = store,
                                path = CompletedTracePath(
                                    radioID = radio,
                                    name = draft.trim(),
                                    hashSize = hashSize,
                                    batchResults = if (batchEnabled) batchResults else emptyList(),
                                    result = result,
                                ),
                                isCurrentOwner = isCurrentOwner,
                            )
                        }
                        if (!isCurrentOwner()) return@launch
                        onReloadSaved()
                        if (!isCurrentOwner()) return@launch
                        if (stored) {
                            haptics.success()
                            onSaveSucceeded()
                        } else {
                            onSaveFailed()
                        }
                        onSaveNameDraft(null)
                    }
                } else {
                    onSaveNameDraft(null)
                }
            },
        )
    }
}

@Composable
internal fun TracePathSaveOutcomeDialogs(
    showingSaveSuccess: Boolean,
    onDismissSaveSuccess: () -> Unit,
    showingSaveFailed: Boolean,
    saveFailedMessage: String,
    onDismissSaveFailed: () -> Unit,
    showingClearPath: Boolean,
    onDismissClearPath: () -> Unit,
    onResetPath: () -> Unit,
) {
    if (showingSaveSuccess) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_trace_map_saved_title),
            message = stringResource(Res.string.contacts_trace_map_saved_message),
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = onDismissSaveSuccess,
        )
    }
    if (showingSaveFailed) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_trace_map_save_failed_title),
            message = saveFailedMessage,
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = onDismissSaveFailed,
        )
    }
    if (showingClearPath) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_trace_clear_path),
            message = stringResource(Res.string.contacts_trace_clear_path_message),
            confirmLabel = stringResource(Res.string.contacts_trace_clear_path),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                onResetPath()
                onDismissClearPath()
            },
            onDismiss = onDismissClearPath,
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun TracePathErrorCover(
    errorMessage: String?,
    loadFailedMessage: String,
    renameFailedMessage: String,
    deleteFailedMessage: String,
    onDismiss: () -> Unit,
) {
    errorMessage?.let { message ->
        val savedPathFailure =
            message == loadFailedMessage ||
                message == renameFailedMessage ||
                message == deleteFailedMessage
        ThreadErrorDialog(
            message = message,
            onDismiss = onDismiss,
            dialogTitle = if (savedPathFailure) {
                null
            } else {
                stringResource(Res.string.contacts_trace_failed)
            },
        )
    }
}
