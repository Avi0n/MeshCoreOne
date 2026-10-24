// AndroidOnly: WP-407 User-selected SAF backup documents; bounded reads, cancellable IO and typed failures.
package com.meshcoreone.android.app.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.core.model.BackupContract
import com.meshcoreone.android.feature.settings.app.backup.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BackupDocuments(
    private val context: Context,
    private val create: (String) -> Unit,
    private val open: () -> Unit,
    private val validateSize: (Long) -> Unit = BackupContract::validateCompressedSize,
) : BackupDocumentPort {
    private val operation = Mutex()
    private var pending: CompletableDeferred<Uri?>? = null
    var savedUri: Uri? = null
        private set

    fun result(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }

    fun close() {
        pending?.cancel()
        pending = null
    }

    private suspend fun choose(launch: () -> Unit): Uri? {
        if (pending != null) throw BackupDocumentException.UriUnavailable(IllegalStateException("A document picker is already active"))
        val answer = CompletableDeferred<Uri?>()
        pending = answer
        try {
            launch()
        } catch (failure: RuntimeException) {
            pending = null
            throw BackupDocumentException.UriUnavailable(failure)
        }
        // Cancellation does not clear the pending picker: its late result must never answer a newer request.
        return answer.await()
    }

    override suspend fun save(suggestedName: String, data: ByteArray): BackupSaveOutcome = operation.withLock {
        savedUri = null
        val uri = choose { create(suggestedName) } ?: return@withLock BackupSaveOutcome.Cancelled
        var written = false
        try {
            withContext(Dispatchers.IO) {
                val name = displayName(uri) ?: suggestedName
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw BackupDocumentException.UriUnavailable()
                output.use {
                    var offset = 0
                    while (offset < data.size) {
                        ensureActive()
                        val size = minOf(16 * 1024, data.size - offset)
                        it.write(data, offset, size)
                        offset += size
                    }
                    it.flush()
                }
                written = true
                BackupSaveOutcome.Saved(name)
            }.also { savedUri = uri }
        } catch (cancelled: CancellationException) {
            if (written) savedUri = uri
            else removeIncomplete(uri, cancelled)
            throw cancelled
        } catch (failure: SecurityException) {
            removeIncomplete(uri, failure)
            throw BackupDocumentException.PermissionRevoked(failure)
        } catch (failure: IOException) {
            removeIncomplete(uri, failure)
            if (generateSequence<Throwable>(failure) { it.cause }.any {
                it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
            }) throw BackupDocumentException.InsufficientSpace(failure)
            throw BackupDocumentException.WriteFailed(failure)
        } catch (failure: BackupDocumentException) {
            removeIncomplete(uri, failure)
            throw failure
        } catch (failure: RuntimeException) {
            removeIncomplete(uri, failure)
            throw BackupDocumentException.WriteFailed(failure)
        }
    }

    private suspend fun removeIncomplete(uri: Uri, failure: Throwable) = withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (!android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw IOException("Provider did not remove incomplete output")
            }
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
            java.util.logging.Logger.getLogger("com.mc1.BackupDocuments").log(
                java.util.logging.Level.WARNING, "Could not remove incomplete user-selected output", cleanup,
            )
        }
    }
    override suspend fun pick(): BackupPickOutcome = operation.withLock {
        val uri = choose(open) ?: return@withLock BackupPickOutcome.Cancelled
        val length = withContext(Dispatchers.IO) { size(uri) }
        length?.let(validateSize)
        BackupPickOutcome.Picked(object : BackupSource {
            override val sizeBytes = length
            override suspend fun readBytes(): ByteArray = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw BackupDocumentException.UriUnavailable()
                    input.use {
                        val output = ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        var count = 0L
                        while (true) {
                            ensureActive()
                            val read = it.read(chunk)
                            if (read < 0) break
                            count = Math.addExact(count, read.toLong())
                            validateSize(count)
                            output.write(chunk, 0, read)
                        }
                        output.toByteArray()
                    }
                } catch (failure: SecurityException) {
                    throw BackupDocumentException.PermissionRevoked(failure)
                } catch (failure: IOException) {
                    throw BackupDocumentException.UriUnavailable(failure)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    throw BackupDocumentException.UriUnavailable(failure)
                }
            }
        })
    }

    private fun displayName(uri: Uri): String? =
        query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

    private fun size(uri: Uri): Long? =
        query(uri, OpenableColumns.SIZE) { if (it.isNull(0)) null else it.getLong(0).takeIf { size -> size >= 0 } }

    private fun <T> query(uri: Uri, column: String, read: (android.database.Cursor) -> T): T? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst()) read(it) else null
        }
    } catch (failure: SecurityException) {
        throw BackupDocumentException.PermissionRevoked(failure)
    } catch (failure: RuntimeException) {
        throw BackupDocumentException.UriUnavailable(failure)
    }
}

@Composable
internal fun rememberBackupDocuments(): BackupDocuments {
    val context = LocalContext.current
    var documents by remember { mutableStateOf<BackupDocuments?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        documents?.result(it)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { documents?.result(it) }
    val result = remember(context, create, open) {
        BackupDocuments(context, create::launch, open = { open.launch(arrayOf("application/octet-stream", "*/*")) })
    }
    SideEffect { documents = result }
    DisposableEffect(result) { onDispose { result.close() } }
    return result
}
