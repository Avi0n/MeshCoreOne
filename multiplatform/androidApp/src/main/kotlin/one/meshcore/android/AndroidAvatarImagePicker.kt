package one.meshcore.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.MAX_BACKUP_COMPRESSED_BYTES
import one.meshcore.shared.backup.readBounded
import one.meshcore.shared.contact.ui.AvatarImagePicker
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android [AvatarImagePicker]: the system photo picker for Choose Photo, and Storage Access
 * Framework `GetContent` for Choose File (iOS Files). Neither needs a runtime permission. Picked
 * bytes are read off the content URI; the shared contact-detail screen crops, then stores them.
 */
@Composable
fun rememberAndroidAvatarImagePicker(): AvatarImagePicker {
    val context = LocalContext.current
    val container = (context.applicationContext as MeshCoreApplication).container
    val pending = remember { mutableStateOf<((ByteArray) -> Unit)?>(null) }
    val active = remember { AtomicBoolean(true) }
    val contentResolver = context.applicationContext.contentResolver

    DisposableEffect(Unit) {
        onDispose { active.set(false) }
    }

    fun deliver(uri: Uri?) {
        val callback = pending.value
        pending.value = null
        if (uri == null) return
        // The process scope lets the read finish across Activity recreation; a disposed picker leaves
        // the bytes for the next composition instead of invoking its stale screen callback.
        container.appScope.launch {
            val bytes = withContext(container.ioDispatcher) {
                runCatching {
                    // Same ceiling as a backup file. A content URI is not buffered past it.
                    contentResolver.openInputStream(uri)?.use { stream ->
                        readBounded(
                            read = { buffer -> stream.read(buffer) },
                            maxBytes = MAX_BACKUP_COMPRESSED_BYTES,
                        )
                    }
                }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) {
                if (callback != null && active.get()) {
                    callback(bytes)
                } else {
                    container.leftoverAvatarBytes = bytes
                }
            }
        }
    }

    fun start(onPicked: (ByteArray) -> Unit, open: () -> Unit) {
        val leftover = container.leftoverAvatarBytes
        if (leftover != null && leftover.isNotEmpty()) {
            container.leftoverAvatarBytes = null
            onPicked(leftover)
            return
        }
        pending.value = onPicked
        open()
    }

    val photoLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        deliver(uri)
    }
    val fileLauncher = rememberLauncherForActivityResult(GetContent()) { uri ->
        deliver(uri)
    }
    return remember(photoLauncher, fileLauncher) {
        object : AvatarImagePicker {
            override val isSupported: Boolean = true
            override val isFilePickerSupported: Boolean = true

            override fun launch(onPicked: (ByteArray) -> Unit) {
                start(onPicked) {
                    photoLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                }
            }

            override fun launchFromFiles(onPicked: (ByteArray) -> Unit) {
                start(onPicked) {
                    fileLauncher.launch("image/*")
                }
            }
        }
    }
}
