package one.meshcore.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.BackupExportClock
import one.meshcore.shared.backup.readBackupDocument
import one.meshcore.shared.backup.readTextDocument
import one.meshcore.shared.backup.suggestedBackupFilename
import java.util.Calendar

/**
 * Backup and node-config document pickers for [MainActivity]. The activity still owns the
 * result launchers; these functions only wait on the pick and read or write the stream.
 */
internal class MainActivityDocuments(
    private val container: AppContainer,
    private val contentResolver: ContentResolver,
    private val createBackupDocument: ActivityResultLauncher<String>,
    private val openBackupDocument: ActivityResultLauncher<Array<String>>,
    private val openNodeConfigDocument: ActivityResultLauncher<Array<String>>,
) {
    /**
     * Writes the compressed `.mc1backup` to a user-chosen document. Null when the save
     * picker is cancelled, matching iOS `fileExporter` cancel → idle.
     */
    internal suspend fun saveBackupDocument(compressed: ByteArray): String? {
        val leftover = container.backupCreateWaiter.takeLeftover()
        val suggested = backupFilenameSuggestion()
        val uri = leftover ?: run {
            createBackupDocument.launch(suggested)
            container.backupCreateWaiter.awaitPick()
        } ?: return null
        return writeBackupDocument(uri, compressed, suggested)
    }

    internal suspend fun saveBackupDocumentToUri(uri: Uri, compressed: ByteArray): String =
        writeBackupDocument(uri, compressed, backupFilenameSuggestion())

    private suspend fun backupFilenameSuggestion(): String {
        val calendar = Calendar.getInstance()
        return suggestedBackupFilename(
            BackupExportClock(
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
                hour = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE),
                second = calendar.get(Calendar.SECOND),
            ),
        )
    }

    private suspend fun writeBackupDocument(uri: Uri, compressed: ByteArray, suggested: String): String =
        withContext(container.ioDispatcher) {
            contentResolver.openOutputStream(uri)?.use { it.write(compressed) }
                ?: error("Could not write backup file")
            savedBackupFilename(uri) ?: suggested
        }

    private fun savedBackupFilename(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) cursor.getString(column)?.takeIf { it.isNotBlank() } else null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Product file import: pick and decompress only. Merge waits for the preview confirm
     * (`importBackup`), matching iOS `loadAndParseBackup` then `ImportPreviewSheet`.
     */
    internal suspend fun pickBackupFileJson(): String? {
        val leftover = container.backupPickWaiter.takeLeftover()
        val uri = leftover ?: run {
            openBackupDocument.launch(
                arrayOf("*/*", AndroidSystemBridges.BACKUP_MIME_TYPE, "application/octet-stream"),
            )
            container.backupPickWaiter.awaitPick()
        } ?: return null
        return readBackupFileJson(uri)
    }

    internal suspend fun readBackupFileJson(uri: Uri): String = withContext(container.ioDispatcher) {
        contentResolver.openInputStream(uri)?.use { stream ->
            readBackupDocument(read = { buffer -> stream.read(buffer) })
        }
    } ?: error("Could not read backup file")

    internal suspend fun pickNodeConfigJsonText(): String? {
        val uri = container.nodeConfigPickWaiter.pick {
            openNodeConfigDocument.launch(arrayOf(AndroidSystemBridges.JSON_MIME_TYPE, "application/*", "*/*"))
        } ?: return null
        return readNodeConfigJsonText(uri)
    }

    internal suspend fun readNodeConfigJsonText(uri: Uri): String = withContext(container.ioDispatcher) {
        contentResolver.openInputStream(uri)?.use { stream ->
            readTextDocument(read = { buffer -> stream.read(buffer) })
        }
    } ?: error("Could not read config file")
}
