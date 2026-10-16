package one.meshcore.desktop

import one.meshcore.shared.backup.withBackupFileExtension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

/**
 * Desktop Settings → Backup file pickers. Save uses a native [FileDialog] and forces the
 * `.mc1backup` suffix; open returns the chosen file as-is so a raw JSON debug dump still imports.
 */
internal object DesktopBackupFiles {
    fun resolveSaveFile(directory: String?, fileName: String?): File? {
        if (directory.isNullOrBlank() || fileName.isNullOrBlank()) return null
        return File(directory, withBackupFileExtension(fileName))
    }

    fun pickSaveFile(title: String, suggestedName: String): File? = runOnEdt {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        resolveSaveFile(dialog.directory, dialog.file)
    }

    fun pickSaveNamedFile(title: String, suggestedName: String): File? = runOnEdt {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val directory = dialog.directory
        val fileName = dialog.file
        if (directory.isNullOrBlank() || fileName.isNullOrBlank()) return@runOnEdt null
        File(directory, fileName)
    }

    fun pickOpenFile(title: String): File? = runOnEdt {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory
        val fileName = dialog.file
        if (directory.isNullOrBlank() || fileName.isNullOrBlank()) return@runOnEdt null
        File(directory, fileName)
    }

    private fun <T> runOnEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
