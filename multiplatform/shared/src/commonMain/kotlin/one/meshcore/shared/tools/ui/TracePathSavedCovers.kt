package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_common_save
import one.meshcore.shared.resources.contacts_saved_paths_delete_message
import one.meshcore.shared.resources.contacts_saved_paths_delete_title
import one.meshcore.shared.resources.contacts_saved_paths_rename_title
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.preferenceHubInset
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TracePathSavedPathsCover(
    showingSavedPaths: Boolean,
    savedPaths: List<SavedTracePathDto>,
    nowSeconds: Long,
    onDismissSavedPaths: () -> Unit,
    onSelectSavedPath: (SavedTracePathDto) -> Unit,
    onStartRename: (SavedTracePathDto) -> Unit,
    onAskDelete: (SavedTracePathDto) -> Unit,
    detailPath: SavedTracePathDto?,
    onDismissDetail: () -> Unit,
    onLoadDetail: (SavedTracePathDto) -> Unit,
) {
    if (showingSavedPaths) {
        SavedPathsListSheet(
            paths = savedPaths,
            nowSeconds = nowSeconds,
            onDismiss = onDismissSavedPaths,
            onSelect = onSelectSavedPath,
            onRename = onStartRename,
            onDelete = onAskDelete,
        )
    }
    detailPath?.let { path ->
        SavedPathDetailSheet(
            path = path,
            onDismiss = onDismissDetail,
            onLoad = { onLoadDetail(path) },
            onRename = { onStartRename(path) },
            onDelete = { onAskDelete(path) },
        )
    }
}

@Composable
internal fun TracePathRenameCover(
    renameDraft: String?,
    renamePath: SavedTracePathDto?,
    onRenameDraft: (String?) -> Unit,
    onRenamePath: (SavedTracePathDto?) -> Unit,
    savedPathStore: SavedTracePathStore?,
    scope: CoroutineScope,
    detailPath: SavedTracePathDto?,
    onDetailPath: (SavedTracePathDto?) -> Unit,
    onSavedPaths: (List<SavedTracePathDto>) -> Unit,
    onError: (String) -> Unit,
    loadFailedMessage: String,
    renameFailedMessage: String,
    radioId: () -> Uuid?,
    isCurrentOwner: () -> Boolean,
) {
    renameDraft?.let { draft ->
        val path = renamePath
        if (path != null) {
            FullScreenOverlay(
                onDismiss = {
                    onRenameDraft(null)
                    onRenamePath(null)
                },
                title = stringResource(Res.string.contacts_saved_paths_rename_title),
            ) { innerPadding ->
                Column(modifier = Modifier.preferenceHubInset(innerPadding)) {
                    PreferenceHubSection {
                        AppOutlinedTextField(
                            value = draft,
                            onValueChange = { onRenameDraft(it) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(PreferenceHubCardPadding),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val store = savedPathStore ?: return@Button
                            val name = draft.trim().ifBlank { path.name }
                            scope.launch {
                                renameSavedTracePath(
                                    TracePathRenameRequest(
                                        store = store,
                                        pathId = path.id,
                                        name = name,
                                        radioId = radioId,
                                        isCurrentOwner = isCurrentOwner,
                                        detailPathId = detailPath?.id,
                                        loadFailedMessage = loadFailedMessage,
                                        renameFailedMessage = renameFailedMessage,
                                        onSavedPaths = onSavedPaths,
                                        onError = onError,
                                        onDetailPath = onDetailPath,
                                    ),
                                )
                                if (!isCurrentOwner()) return@launch
                                onRenameDraft(null)
                                onRenamePath(null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PreferenceHubHorizontalPadding),
                    ) {
                        Text(stringResource(Res.string.contacts_common_save))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TracePathDeleteCover(
    deleteConfirm: SavedTracePathDto?,
    onDismissDelete: () -> Unit,
    savedPathStore: SavedTracePathStore?,
    scope: CoroutineScope,
    radioId: () -> Uuid?,
    isCurrentOwner: () -> Boolean,
    onSavedPaths: (List<SavedTracePathDto>) -> Unit,
    onError: (String) -> Unit,
    onDetailPath: (SavedTracePathDto?) -> Unit,
    loadFailedMessage: String,
    deleteFailedMessage: String,
) {
    deleteConfirm?.let { path ->
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_saved_paths_delete_title),
            message = formatTemplate(
                stringResource(Res.string.contacts_saved_paths_delete_message),
                listOf(path.name),
            ),
            confirmLabel = stringResource(Res.string.contacts_common_delete),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                val store = savedPathStore ?: return@ConfirmAlertDialog
                scope.launch {
                    confirmSavedTracePathDelete(
                        TracePathDeleteRequest(
                            store = store,
                            pathId = path.id,
                            radioId = radioId,
                            isCurrentOwner = isCurrentOwner,
                            loadFailedMessage = loadFailedMessage,
                            deleteFailedMessage = deleteFailedMessage,
                            onSavedPaths = onSavedPaths,
                            onError = onError,
                            onDetailPath = onDetailPath,
                        ),
                    )
                    if (isCurrentOwner()) onDismissDelete()
                }
            },
            onDismiss = onDismissDelete,
        )
    }
}
