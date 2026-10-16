package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.backup.BackupEnvelopeBuilder
import one.meshcore.shared.backup.BackupExportInput
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.previewPickedBackupJson
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.settings_backup_export_alert_export
import one.meshcore.shared.resources.settings_backup_export_success_title
import one.meshcore.shared.resources.settings_backup_export_title
import one.meshcore.shared.resources.settings_backup_import_cancelled_title
import one.meshcore.shared.resources.settings_backup_import_preview_button
import one.meshcore.shared.resources.settings_backup_import_preview_cancel
import one.meshcore.shared.resources.settings_backup_import_preview_title
import one.meshcore.shared.resources.settings_backup_import_progress
import one.meshcore.shared.resources.settings_backup_import_success_title
import one.meshcore.shared.resources.settings_backup_import_title
import org.jetbrains.compose.resources.stringResource

internal class BackupSettingsLifetimeFixture(scope: CoroutineScope) {
    val workspace = BackupSettingsWorkspace(scope)
    val shown = mutableStateOf(true)
    val mount = mutableStateOf(0)
    var pickReply = CompletableDeferred<String?>()
    var exportReply = CompletableDeferred<BackupExportSuccessSummary?>()
    var importReply = CompletableDeferred<BackupImportResult?>()
    var pickCalls = 0
    var exportCalls = 0
    var importCalls = 0
    var cancelledImports = 0
    val importedJson = mutableListOf<String>()
    val labels = Labels()
    val json = BackupEnvelopeBuilder.encode(
        BackupExportInput(appVersion = APP_VERSION, appBuild = APP_BUILD, exportDate = EXPORT_DATE),
    )
    val summary = BackupExportSuccessSummary(FILENAME, COMPRESSED_BYTES, previewPickedBackupJson(json))

    @Composable
    fun Content() {
        labels.importFile = stringResource(Res.string.settings_backup_import_title)
        labels.export = stringResource(Res.string.settings_backup_export_title)
        labels.confirmExport = stringResource(Res.string.settings_backup_export_alert_export)
        labels.preview = stringResource(Res.string.settings_backup_import_preview_title)
        labels.confirmImport = stringResource(Res.string.settings_backup_import_preview_button)
        labels.importing = stringResource(Res.string.settings_backup_import_progress)
        labels.cancel = stringResource(Res.string.settings_backup_import_preview_cancel)
        labels.cancelled = stringResource(Res.string.settings_backup_import_cancelled_title)
        labels.importSuccess = stringResource(Res.string.settings_backup_import_success_title)
        labels.exportSuccess = stringResource(Res.string.settings_backup_export_success_title)
        labels.ok = stringResource(Res.string.localizable_common_ok)
        TimestampFormatting {
            MaterialTheme {
                if (shown.value) {
                    key(mount.value) {
                        Box(Modifier.testTag(MOUNT_TAG)) {
                            BackupSettingsScreen(
                                isRadioConnected = false,
                                workspace = workspace,
                                onImportFile = {
                                    pickCalls += 1
                                    pickReply.await()
                                },
                                onExportConfirmed = {
                                    exportCalls += 1
                                    exportReply.await()
                                },
                                onImportConfirmed = { picked ->
                                    importCalls += 1
                                    importedJson += picked
                                    try {
                                        importReply.await()
                                    } catch (cancelled: CancellationException) {
                                        cancelledImports += 1
                                        throw cancelled
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    class Labels {
        lateinit var importFile: String
        lateinit var export: String
        lateinit var confirmExport: String
        lateinit var preview: String
        lateinit var confirmImport: String
        lateinit var importing: String
        lateinit var cancel: String
        lateinit var cancelled: String
        lateinit var importSuccess: String
        lateinit var exportSuccess: String
        lateinit var ok: String
    }

    companion object {
        const val MOUNT_TAG = "backup-screen-mount"
        const val FILENAME = "retained-backup.mc1backup"
        private const val APP_VERSION = "1.5.3"
        private const val APP_BUILD = "1"
        private const val EXPORT_DATE = 1_800_000_000.0
        private const val COMPRESSED_BYTES = 128
    }
}
