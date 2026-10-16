package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.settings_backup_import_preview_cancel
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_confirm
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_message
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_none_found
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_partial
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_result_title
import one.meshcore.shared.resources.settings_danger_zone_alert_remove_unfavorited_title
import one.meshcore.shared.resources.settings_danger_zone_alert_reset_confirm
import one.meshcore.shared.resources.settings_danger_zone_alert_reset_message
import one.meshcore.shared.resources.settings_danger_zone_alert_reset_title
import one.meshcore.shared.resources.settings_danger_zone_dialog_forget_delete_all
import one.meshcore.shared.resources.settings_danger_zone_dialog_forget_keep_data
import one.meshcore.shared.resources.settings_danger_zone_dialog_forget_message
import one.meshcore.shared.resources.settings_danger_zone_dialog_forget_title
import one.meshcore.shared.resources.settings_danger_zone_error_services_unavailable
import one.meshcore.shared.resources.settings_danger_zone_factory_reset
import one.meshcore.shared.resources.settings_danger_zone_footer
import one.meshcore.shared.resources.settings_danger_zone_forget_device
import one.meshcore.shared.resources.settings_danger_zone_header
import one.meshcore.shared.resources.settings_danger_zone_remove_unfavorited
import one.meshcore.shared.resources.settings_danger_zone_removed
import one.meshcore.shared.resources.settings_danger_zone_removing
import one.meshcore.shared.resources.settings_danger_zone_resetting
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formattedPluralStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.preferenceHubOverlayFill
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/**
 * Settings danger zone: forget device, factory reset, and remove non-favorite nodes. Forget uses a
 * nested overlay with Keep data / Delete all; factory reset and remove-unfavorited stay confirm
 * dialogs. Remove-unfavorited reports partial results when the radio drops mid-pass.
 */
@Composable
fun DangerZoneSettingsScreen(
    isDeviceConnected: Boolean,
    onForgetKeepData: suspend () -> Unit = {},
    onForgetDeleteData: suspend () -> Unit = {},
    onFactoryReset: suspend () -> Boolean = { true },
    onLeaveAfterDestructive: () -> Unit = {},
    unfavoritedCount: suspend () -> Int = { 0 },
    onRemoveUnfavorited: suspend () -> StaleNodeRemovalResult = {
        StaleNodeRemovalResult(removed = 0, total = 0)
    },
    modifier: Modifier = Modifier,
    /** True when this is a section of Advanced (iOS `DangerZoneSection`), so the parent owns scrolling. */
    embedded: Boolean = false,
) {
    var showForget by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var pendingRemoveCount by remember { mutableStateOf<Int?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var partialRemoval by remember { mutableStateOf<StaleNodeRemovalResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isRemoving by remember { mutableStateOf(false) }
    var isResetting by remember { mutableStateOf(false) }
    var showRemoveSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsErrorText = rememberSettingsErrorText()
    val noneFound = stringResource(Res.string.settings_danger_zone_alert_remove_unfavorited_none_found)
    val servicesUnavailable = stringResource(Res.string.settings_danger_zone_error_services_unavailable)

    Column(
        modifier
            .fillMaxWidth()
            .then(dangerZoneColumnModifier(embedded)),
    ) {
        PreferenceHubSection(
            title = dangerZoneSectionTitle(
                embedded,
                stringResource(Res.string.settings_danger_zone_header),
            ),
            applyHorizontalPadding = !embedded,
        ) {
            Column(
                modifier = Modifier.padding(PreferenceHubCardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showForget = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonIconLabel(
                        title = stringResource(Res.string.settings_danger_zone_forget_device),
                        icon = AppIcons.Delete,
                    )
                }
                Button(
                    onClick = { showReset = true },
                    enabled = dangerZoneResetEnabled(isDeviceConnected, isResetting),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().radioConnectionHint(isDeviceConnected),
                ) {
                    DangerZoneResetButtonContent(isResetting = isResetting)
                }
                OutlinedButton(
                    onClick = {
                        launchDangerZoneUnfavoritedCount(
                            enabled = dangerZoneRemoveEnabled(
                                isDeviceConnected = isDeviceConnected,
                                isRemoving = isRemoving,
                                showRemoveSuccess = showRemoveSuccess,
                            ),
                            scope = scope,
                            unfavoritedCount = unfavoritedCount,
                            callbacks = DangerZoneCountCallbacks(
                                onNone = { resultMessage = noneFound },
                                onSome = { pendingRemoveCount = it },
                                onFailure = { errorMessage = settingsErrorText(it) },
                                setRemoving = { isRemoving = it },
                            ),
                        )
                    },
                    enabled = dangerZoneRemoveEnabled(isDeviceConnected, isRemoving, showRemoveSuccess),
                    colors = dangerZoneRemoveButtonColors(showRemoveSuccess),
                    modifier = Modifier.fillMaxWidth().radioConnectionHint(isDeviceConnected),
                ) {
                    DangerZoneRemoveButtonContent(
                        label = dangerZoneRemoveLabel(
                            isRemoving = isRemoving,
                            pendingRemoveCount = pendingRemoveCount,
                            showRemoveSuccess = showRemoveSuccess,
                        ),
                    )
                }
            }
        }
        PreferenceHubFooter(
            text = stringResource(Res.string.settings_danger_zone_footer),
            applyHorizontalPadding = !embedded,
        )
    }

    if (showForget) {
        // Nested Up / overlay dismiss never runs delete-all — that was the data-loss trap (DZ-1).
        FullScreenOverlay(
            onDismiss = { showForget = false },
            title = stringResource(Res.string.settings_danger_zone_dialog_forget_title),
        ) { innerPadding ->
            Column(
                modifier = Modifier.preferenceHubOverlayFill(innerPadding),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                Column(modifier = preferenceHubFormScroll()) {
                    PreferenceHubSection {
                        PreferenceHubStatusRow(
                            stringResource(Res.string.settings_danger_zone_dialog_forget_message),
                        )
                    }
                }
                Button(
                    onClick = {
                        showForget = false
                        scope.launch {
                            runDangerZoneForget(
                                action = onForgetKeepData,
                                onLeft = onLeaveAfterDestructive,
                                onFailure = { errorMessage = settingsErrorText(it) },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.settings_danger_zone_dialog_forget_keep_data))
                }
                OutlinedButton(
                    onClick = {
                        showForget = false
                        scope.launch {
                            runDangerZoneForget(
                                action = onForgetDeleteData,
                                onLeft = onLeaveAfterDestructive,
                                onFailure = { errorMessage = settingsErrorText(it) },
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.settings_danger_zone_dialog_forget_delete_all))
                }
            }
        }
    }
    if (showReset) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_danger_zone_alert_reset_title),
            message = stringResource(Res.string.settings_danger_zone_alert_reset_message),
            confirmLabel = stringResource(Res.string.settings_danger_zone_alert_reset_confirm),
            dismissLabel = stringResource(Res.string.settings_backup_import_preview_cancel),
            onConfirm = {
                showReset = false
                scope.launch {
                    runDangerZoneFactoryReset(
                        reset = onFactoryReset,
                        onLeft = onLeaveAfterDestructive,
                        onUnavailable = { errorMessage = servicesUnavailable },
                        onFailure = { errorMessage = settingsErrorText(it) },
                        setResetting = { isResetting = it },
                    )
                }
            },
            onDismiss = { showReset = false },
            confirmButtonColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        )
    }
    pendingRemoveCount?.let { count ->
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_danger_zone_alert_remove_unfavorited_title),
            message = formattedPluralStringResource(
                Res.plurals.settings_danger_zone_alert_remove_unfavorited_message,
                count,
                count,
            ),
            confirmLabel = stringResource(Res.string.settings_danger_zone_alert_remove_unfavorited_confirm),
            dismissLabel = stringResource(Res.string.settings_backup_import_preview_cancel),
            onConfirm = {
                pendingRemoveCount = null
                scope.launch {
                    applyDangerZoneRemoval(
                        remove = onRemoveUnfavorited,
                        onFull = {
                            showRemoveSuccess = true
                            delay(REMOVE_SUCCESS_DISPLAY_MS)
                            showRemoveSuccess = false
                        },
                        onPartial = { partialRemoval = it },
                        onFailure = { errorMessage = settingsErrorText(it) },
                        setRemoving = { isRemoving = it },
                    )
                }
            },
            onDismiss = { pendingRemoveCount = null },
        )
    }
    resultMessage?.let { message ->
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.settings_danger_zone_alert_remove_unfavorited_result_title),
            message = message,
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = { resultMessage = null },
        )
    }
    partialRemoval?.let { result ->
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.settings_danger_zone_alert_remove_unfavorited_result_title),
            message = formattedPluralStringResource(
                Res.plurals.settings_danger_zone_alert_remove_unfavorited_partial,
                result.total,
                result.removed,
                result.total,
            ),
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = { partialRemoval = null },
        )
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

@Composable
private fun dangerZoneColumnModifier(embedded: Boolean): Modifier = if (embedded) {
    Modifier
} else {
    Modifier
        .verticalScroll(rememberScrollState())
        .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap)
}

@Composable
private fun dangerZoneRemoveButtonColors(showRemoveSuccess: Boolean) = if (showRemoveSuccess) {
    ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.primary)
} else {
    ButtonDefaults.outlinedButtonColors()
}

@Composable
private fun DangerZoneResetButtonContent(isResetting: Boolean) {
    if (isResetting) {
        CircularProgressIndicator(
            modifier = Modifier.size(ButtonDefaults.IconSize),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onError,
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(Res.string.settings_danger_zone_resetting))
    } else {
        ButtonIconLabel(
            title = stringResource(Res.string.settings_danger_zone_factory_reset),
            icon = AppIcons.Warning,
        )
    }
}

@Composable
private fun DangerZoneRemoveButtonContent(label: DangerZoneRemoveLabel) {
    when (label) {
        DangerZoneRemoveLabel.Counting ->
            CircularProgressIndicator(
                modifier = Modifier.size(ButtonDefaults.IconSize),
                strokeWidth = 2.dp,
            )
        DangerZoneRemoveLabel.Removing ->
            Text(stringResource(Res.string.settings_danger_zone_removing))
        DangerZoneRemoveLabel.Success -> {
            Icon(
                imageVector = AppIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.settings_danger_zone_removed))
        }
        DangerZoneRemoveLabel.Idle -> ButtonIconLabel(
            title = stringResource(Res.string.settings_danger_zone_remove_unfavorited),
            icon = AppIcons.GroupOff,
        )
    }
}

/** iOS `DangerZoneViewModel.removeSuccessDisplayDuration`. */
private const val REMOVE_SUCCESS_DISPLAY_MS = 1_500L
