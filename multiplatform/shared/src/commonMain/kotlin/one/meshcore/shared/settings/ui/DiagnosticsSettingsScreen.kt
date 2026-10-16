package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.settings_diagnostics_alert_clear_confirm
import one.meshcore.shared.resources.settings_diagnostics_alert_clear_message
import one.meshcore.shared.resources.settings_diagnostics_alert_clear_title
import one.meshcore.shared.resources.settings_diagnostics_clear_logs
import one.meshcore.shared.resources.settings_diagnostics_error_export_failed
import one.meshcore.shared.resources.settings_diagnostics_export_logs
import one.meshcore.shared.resources.settings_diagnostics_footer
import one.meshcore.shared.resources.settings_diagnostics_header
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubScroll
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Diagnostics: export/clear debug log actions. [onExportLogs] returns shareable text;
 * [onClearLogs] wipes the store. Feedback embeds the same section via [DiagnosticsLogSection].
 */
@Composable
fun DiagnosticsSettingsScreen(
    onExportLogs: suspend () -> String = { "" },
    onClearLogs: suspend () -> Unit = {},
    onShareExport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.preferenceHubScroll()) {
        DiagnosticsLogSection(
            onExportLogs = onExportLogs,
            onClearLogs = onClearLogs,
            onShareExport = onShareExport,
            showHeader = false,
        )
    }
}

/** Export and clear actions used by Diagnostics and by Feedback (iOS `DiagnosticsSection`). */
@Composable
internal fun DiagnosticsLogSection(
    onExportLogs: suspend () -> String,
    onClearLogs: suspend () -> Unit,
    onShareExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    var showClear by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportFailed = stringResource(Res.string.settings_diagnostics_error_export_failed)
    val settingsErrorText = rememberSettingsErrorText()

    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(
            title = if (showHeader) {
                stringResource(Res.string.settings_diagnostics_header)
            } else {
                null
            },
        ) {
            Column(
                modifier = Modifier.padding(PreferenceHubCardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (isExporting) return@Button
                        isExporting = true
                        scope.launch {
                            try {
                                val result = runDiagnosticsLogOperation { onExportLogs() }
                                val exportError = diagnosticsExportErrorMessage(result, exportFailed)
                                if (exportError != null) {
                                    errorMessage = exportError
                                } else {
                                    errorMessage = null
                                    onShareExport(result.getOrThrow())
                                }
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    enabled = diagnosticsExportEnabled(isExporting),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.Share,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.settings_diagnostics_export_logs))
                }
                OutlinedButton(onClick = { showClear = true }, modifier = Modifier.fillMaxWidth()) {
                    ButtonIconLabel(
                        title = stringResource(Res.string.settings_diagnostics_clear_logs),
                        icon = AppIcons.Delete,
                    )
                }
            }
        }
        PreferenceHubFooter(stringResource(Res.string.settings_diagnostics_footer))
    }

    if (showClear) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.settings_diagnostics_alert_clear_title),
            message = stringResource(Res.string.settings_diagnostics_alert_clear_message),
            confirmLabel = stringResource(Res.string.settings_diagnostics_alert_clear_confirm),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                showClear = false
                scope.launch {
                    runDiagnosticsLogOperation { onClearLogs() }
                        .onFailure { errorMessage = settingsErrorText(it) }
                }
            },
            onDismiss = { showClear = false },
        )
    }
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = { errorMessage = null })
    }
}

/**
 * The user-facing export error, or null when [result] holds shareable text. A thrown failure uses the
 * same export-failed copy iOS uses when the file cannot be created — never the exception text.
 */
internal fun diagnosticsExportErrorMessage(result: Result<String>, exportFailed: String): String? {
    val text = result.getOrNull()
    return if (result.isFailure || text.isNullOrBlank()) exportFailed else null
}

/** iOS disables the export row while `LogExportService` runs so a slow store cannot enqueue a second dump. */
internal fun diagnosticsExportEnabled(isExporting: Boolean): Boolean = !isExporting
