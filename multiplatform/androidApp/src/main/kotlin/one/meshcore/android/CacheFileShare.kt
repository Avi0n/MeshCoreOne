package one.meshcore.android

import android.content.ClipData
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shares a cache file through a grantable content URI. Debug logs, chat images, and node-config
 * JSON all use this chooser; only the MIME type and the file they just wrote differ.
 */
internal fun ComponentActivity.shareCacheFile(file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, file.name, uri)
    }
    startActivity(Intent.createChooser(share, null))
}

/** Local-time `yyyy-MM-dd-HHmmss` stamp for export basenames. */
internal fun localExportTimestamp(now: Date = Date()): String =
    SimpleDateFormat(EXPORT_TIMESTAMP_PATTERN, Locale.US).format(now)

private const val EXPORT_TIMESTAMP_PATTERN = "yyyy-MM-dd-HHmmss"
