package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportPreview
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.formatBackupByteCount
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.formatMessageDetailDateTime
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_export_success_announcement
import one.meshcore.shared.resources.settings_backup_export_success_included_section
import one.meshcore.shared.resources.settings_backup_import_cancelled_message
import one.meshcore.shared.resources.settings_backup_import_cancelling
import one.meshcore.shared.resources.settings_backup_import_error_title
import one.meshcore.shared.resources.settings_backup_import_nothing_to_import_subtitle
import one.meshcore.shared.resources.settings_backup_import_preview_app_version
import one.meshcore.shared.resources.settings_backup_import_preview_details
import one.meshcore.shared.resources.settings_backup_import_preview_exported
import one.meshcore.shared.resources.settings_backup_import_preview_info
import one.meshcore.shared.resources.settings_backup_import_success_added_section
import one.meshcore.shared.resources.settings_backup_import_success_already_here_footer
import one.meshcore.shared.resources.settings_backup_import_success_already_here_refreshed
import one.meshcore.shared.resources.settings_backup_import_success_already_here_section
import one.meshcore.shared.resources.settings_backup_import_success_already_here_summary
import one.meshcore.shared.resources.settings_backup_import_success_dropped_section
import one.meshcore.shared.resources.settings_backup_import_success_dropped_summary
import one.meshcore.shared.resources.settings_backup_import_success_subtitle_added
import one.meshcore.shared.resources.settings_backup_import_success_subtitle_refreshed
import one.meshcore.shared.ui.AnnounceOnAppear
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedPluralStringResource
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** iOS `ExportSuccessContent` hero `checkmark.circle.fill` `.largeTitle`. */
private val EXPORT_SUCCESS_HERO_SIZE = 44.dp

@Composable
internal fun BackupImportSheetBody(
    phase: BackupImportSheetPhase,
    preview: BackupImportPreview?,
    importResult: BackupImportResult?,
    failure: BackupFailure?,
    isCancellingImport: Boolean,
) {
    when (phase) {
        BackupImportSheetPhase.NONE -> Unit
        BackupImportSheetPhase.IMPORTING -> {
            AnnounceOnAppear(
                announce = true,
                message = stringResource(backupImportStartedAnnouncementRes()),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                CircularProgressIndicator()
                if (isCancellingImport) {
                    Text(
                        text = stringResource(Res.string.settings_backup_import_cancelling),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        BackupImportSheetPhase.SUCCESS -> importResult?.let { result ->
            ImportSuccessSection(result = result)
        }
        BackupImportSheetPhase.CANCELLED -> {
            AnnounceOnAppear(
                announce = true,
                message = stringResource(backupImportCancelledAnnouncementRes()),
            )
            PreferenceHubSection(applyHorizontalPadding = false) {
                PreferenceHubStatusRow(
                    stringResource(Res.string.settings_backup_import_cancelled_message),
                )
            }
        }
        BackupImportSheetPhase.FAILED -> failure?.takeIf { !it.isExport }?.let { f ->
            ImportFailedSection(failure = f)
        }
        BackupImportSheetPhase.PREVIEW -> preview?.let { p ->
            PreferenceHubSection(
                title = stringResource(Res.string.settings_backup_import_preview_details),
                applyHorizontalPadding = false,
            ) {
                PreferenceHubValueRow(
                    stringResource(Res.string.settings_backup_import_preview_app_version),
                    "${p.appVersion} (${p.appBuild})",
                )
                PreferenceHubValueRow(
                    stringResource(Res.string.settings_backup_import_preview_exported),
                    formatMessageDetailDateTime(
                        p.exportDateEpochSeconds.toDouble(),
                        formatter = LocalTimestampFormatter.current,
                    ),
                )
                backupPreviewRows(p).forEach { row ->
                    PreferenceHubValueRow(stringResource(row.label), row.count.toString())
                }
            }
            PreferenceHubFooter(
                stringResource(Res.string.settings_backup_import_preview_info),
                applyHorizontalPadding = false,
            )
        }
    }
}

@Composable
private fun ImportFailedSection(failure: BackupFailure) {
    val title = stringResource(Res.string.settings_backup_import_error_title)
    val message = backupErrorText(failure)
    AnnounceOnAppear(announce = true, message = title)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(EXPORT_SUCCESS_HERO_SIZE),
        )
        if (backupImportFailureShowsSeparateDetail(failure)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ExportSuccessSection(summary: BackupExportSuccessSummary) {
    val announcement = formattedStringResource(
        Res.string.settings_backup_export_success_announcement,
        summary.filename,
    )
    AnnounceOnAppear(announce = true, message = announcement)
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(EXPORT_SUCCESS_HERO_SIZE),
        )
        Text(
            text = summary.filename,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatBackupByteCount(summary.byteCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val rows = backupPreviewRows(summary.preview)
        if (rows.isNotEmpty()) {
            PreferenceHubSection(
                title = stringResource(Res.string.settings_backup_export_success_included_section),
                applyHorizontalPadding = false,
            ) {
                rows.forEach { row ->
                    ImportCountRow(stringResource(row.label), row.count)
                }
            }
        }
    }
}

@Composable
private fun ImportSuccessSection(result: BackupImportResult) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            PreferenceHubStatusRow(importSuccessHeadline(result))
        }
        val inserted = backupImportInsertedRows(result)
        if (inserted.isNotEmpty()) {
            PreferenceHubSection(
                title = stringResource(Res.string.settings_backup_import_success_added_section),
                applyHorizontalPadding = false,
            ) {
                inserted.forEach { row ->
                    ImportCountRow(stringResource(row.label), row.count)
                }
            }
        }
        val alreadyHere = backupImportAlreadyHereRows(result)
        if (alreadyHere.isNotEmpty()) {
            PreferenceHubSection(
                title = stringResource(Res.string.settings_backup_import_success_already_here_section),
                applyHorizontalPadding = false,
            ) {
                alreadyHere.forEach { row ->
                    ImportCountRow(stringResource(row.label), row.count)
                }
                PreferenceHubStatusRow(
                    formattedPluralStringResource(
                        Res.plurals.settings_backup_import_success_already_here_summary,
                        result.totalSkipped,
                        result.totalSkipped,
                    ),
                )
            }
            PreferenceHubFooter(
                stringResource(Res.string.settings_backup_import_success_already_here_footer),
                applyHorizontalPadding = false,
            )
            if (result.totalMerged > 0) {
                PreferenceHubFooter(
                    formattedPluralStringResource(
                        Res.plurals.settings_backup_import_success_already_here_refreshed,
                        result.totalMerged,
                        result.totalMerged,
                    ),
                    applyHorizontalPadding = false,
                )
            }
        }
        if (result.totalDropped > 0) {
            PreferenceHubSection(
                title = stringResource(Res.string.settings_backup_import_success_dropped_section),
                applyHorizontalPadding = false,
            ) {
                backupImportDroppedRows(result).forEach { row ->
                    ImportCountRow(stringResource(row.label), row.count)
                }
                PreferenceHubStatusRow(
                    formattedPluralStringResource(
                        Res.plurals.settings_backup_import_success_dropped_summary,
                        result.totalDropped,
                        result.totalDropped,
                    ),
                )
            }
            val footer = droppedFooter(result)
            PreferenceHubFooter(
                formatTemplate(stringResource(footer.resource), footer.args),
                applyHorizontalPadding = false,
            )
        }
    }
}

@Composable
private fun importSuccessHeadline(result: BackupImportResult): String = when {
    result.totalInserted > 0 -> formattedPluralStringResource(
        Res.plurals.settings_backup_import_success_subtitle_added,
        result.totalInserted,
        result.totalInserted,
    )
    result.totalMerged > 0 -> formattedPluralStringResource(
        Res.plurals.settings_backup_import_success_subtitle_refreshed,
        result.totalMerged,
        result.totalMerged,
    )
    else -> stringResource(Res.string.settings_backup_import_nothing_to_import_subtitle)
}

@Composable
private fun ImportCountRow(label: String, count: Int) {
    if (count <= 0) return
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(count.toString()) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}
