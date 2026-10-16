package one.meshcore.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Platform bridges the shell hands to `ShellDependencies`: clipboard, share sheets, FileProvider
 * exports, system-settings deep links, and scan haptics. Split from [MainActivity] so the Activity
 * keeps only lifecycle wiring and the file-pick launchers it must register itself.
 */
internal class AndroidSystemBridges(
    private val activity: ComponentActivity,
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidSystemSettings by AndroidSystemSettingsLinks(activity) {
    private fun clipboardManager(): ClipboardManager =
        activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun readClipboardText(): String? =
        clipboardManager().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    fun copyToClipboard(text: String) {
        clipboardManager().setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        performConfirmHaptic()
    }

    fun shareText(text: String, subject: String? = null) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!subject.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
        }
        activity.startActivity(Intent.createChooser(share, null))
    }

    /**
     * Writes a debug-log dump to cache and shares it as a `.txt` stream (iOS
     * `LogExportService.createExportFile`). EXTRA_TEXT cannot hold a 50k-line dump.
     */
    fun shareDebugLogFile(text: String) {
        writeAndShareCacheFile(
            directoryName = DEBUG_LOG_CACHE_DIR,
            fileName = "MeshCore-One-Debug-${localExportTimestamp()}.txt",
            mimeType = "text/plain",
        ) { it.writeText(text) }
    }

    /** Shares already-decoded image bytes as PNG (loaded full-screen still). */
    fun shareImageBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val mime = sniffImageMime(bytes) ?: "image/png"
        val ext = imageExtension(mime, bytes)
        writeAndShareCacheFile(IMAGE_CACHE_DIR, "share.$ext", mime) { it.writeBytes(bytes) }
    }

    /** Writes node-config JSON to cache and shares via FileProvider (companion-app JSON export). */
    fun shareNodeConfigJson(json: String, filename: String) {
        val safeName = filename.ifBlank { "meshcore_config.json" }
        writeAndShareCacheFile(NODE_CONFIG_CACHE_DIR, safeName, JSON_MIME_TYPE) { it.writeText(json) }
    }

    fun performScanHaptic() = performConfirmHaptic()

    /** The success touch mirrored from iOS `sensoryFeedback(.success)`: BLE scan, copy-to-clipboard. */
    private fun performConfirmHaptic() {
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        activity.window.decorView.performHapticFeedback(feedback)
    }

    private fun writeAndShareCacheFile(
        directoryName: String,
        fileName: String,
        mimeType: String,
        write: (File) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val file = withContext(ioDispatcher) {
                val directory = File(activity.cacheDir, directoryName).apply { mkdirs() }
                File(directory, fileName).also(write)
            }
            activity.shareCacheFile(file, mimeType)
        }
    }

    companion object {
        const val CLIP_LABEL = "MeshCore One"
        const val BACKUP_MIME_TYPE = "application/octet-stream"
        const val NODE_CONFIG_CACHE_DIR = "node-config"
        const val JSON_MIME_TYPE = "application/json"
        const val IMAGE_CACHE_DIR = "chat-images"
        const val DEBUG_LOG_CACHE_DIR = "debug-logs"
    }
}

internal fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= GIF_HEADER.size && bytes.copyOf(GIF_HEADER.size).contentEquals(GIF_HEADER)) return "image/gif"
    if (bytes.size >= PNG_HEADER.size && bytes.copyOf(PNG_HEADER.size).contentEquals(PNG_HEADER)) return "image/png"
    if (bytes.size >= JPEG_HEADER.size && bytes.copyOf(JPEG_HEADER.size).contentEquals(JPEG_HEADER)) return "image/jpeg"
    if (bytes.size >= WEBP_HEADER_MIN &&
        bytes.copyOf(RIFF_HEADER.size).contentEquals(RIFF_HEADER) &&
        bytes.copyOfRange(WEBP_FOURCC_OFFSET, WEBP_FOURCC_OFFSET + WEBP_FOURCC.size).contentEquals(WEBP_FOURCC)
    ) {
        return "image/webp"
    }
    return null
}

internal fun imageExtension(mime: String, bytes: ByteArray): String = when {
    mime.contains("png") -> "png"
    mime.contains("gif") -> "gif"
    mime.contains("webp") -> "webp"
    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
    else -> when (sniffImageMime(bytes)) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}

private val GIF_HEADER = byteArrayOf(0x47, 0x49, 0x46)
private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val RIFF_HEADER = byteArrayOf(0x52, 0x49, 0x46, 0x46)
private val WEBP_FOURCC = byteArrayOf(0x57, 0x45, 0x42, 0x50)
private const val WEBP_FOURCC_OFFSET = 8
private const val WEBP_HEADER_MIN = 12
