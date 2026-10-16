package one.meshcore.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException

internal class BackupSaveFilenameFixture(
    val mode: Mode,
    private val chosenName: String = CHOSEN_NAME,
    private val earlyResult: Boolean = false,
    private val cancelPick: Boolean = false,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = instrumentation.targetContext.applicationContext as MeshCoreApplication
    private val container = application.container
    private val file = File.createTempFile("backup-save-name-", ".bin", context.cacheDir)
    private val provider = RecordingProvider()
    private var launches = 0
    private var suggestedName: String? = null

    init {
        check(application.packageName == DEBUG_PACKAGE)
        check(container.backupCreateWaiter.takeLeftover() == null)
    }

    val fallback get() = checkNotNull(suggestedName)
    val expectedName get() = chosenName

    fun save(): Result<String?> {
        val selected = if (cancelPick) null else URI
        if (earlyResult) container.backupCreateWaiter.offer(selected)
        val documents = MainActivityDocuments(
            container = container,
            contentResolver = ContentResolver.wrap(provider),
            createBackupDocument = SaveLauncher { suggested ->
                launches += 1
                suggestedName = suggested
                container.backupCreateWaiter.offer(selected)
            },
            openBackupDocument = UnusedLauncher(),
            openNodeConfigDocument = UnusedLauncher(),
        )
        return runCatching {
            runBlocking { withTimeout(TIMEOUT_MS) { documents.saveBackupDocument(PAYLOAD) } }
        }
    }

    fun assertWriteAndResources() {
        assertEquals(if (earlyResult) 0 else 1, launches)
        assertTrue(provider.queries in 0..1)
        assertTrue(provider.cursors.all { it.isClosed })
        if (cancelPick) {
            assertEquals(0, provider.opens)
            assertEquals(0, provider.queries)
            assertEquals(0L, file.length())
        } else if (mode == Mode.OPEN_FAILURE) {
            assertEquals(1, provider.opens)
            assertEquals(0, provider.queries)
            assertEquals(0L, file.length())
        } else {
            assertEquals(1, provider.opens)
            assertArrayEquals(PAYLOAD, file.readBytes())
            assertFalse(checkNotNull(provider.rawDescriptor).valid())
        }
    }

    fun record(name: String, outcomeMatches: Boolean) {
        writeReceipt(
            name,
            JSONObject().put("expectedOutcome", outcomeMatches).put("opens", provider.opens)
                .put("queries", provider.queries).put("launches", launches)
                .put("writeClosedBeforeQuery", provider.closedBeforeQuery)
                .put("allQueriedCursorsClosed", provider.cursors.all { it.isClosed })
                .put("payloadMatches", file.readBytes().contentEquals(PAYLOAD))
                .put("radioOrSessionConstructedByFixture", false),
        )
    }

    fun close(name: String) {
        try {
            provider.descriptor?.close()
            provider.cursors.forEach { it.close() }
        } finally {
            val removed = !file.exists() || file.delete()
            writeReceipt(
                "$name-teardown",
                JSONObject().put("ownedFileRemoved", removed && !file.exists())
                    .put("allQueriedCursorsClosed", provider.cursors.all { it.isClosed })
                    .put("descriptorClosed", provider.rawDescriptor?.valid() != true),
            )
            check(removed && !file.exists())
        }
    }

    private fun writeReceipt(name: String, receipt: JSONObject) {
        val directory = File(context.getExternalFilesDir(null), "backup-save-name").apply { mkdirs() }
        File(directory, "$name.json").writeText(receipt.toString())
    }

    private inner class RecordingProvider : ContentProvider() {
        var opens = 0
        var queries = 0
        var closedBeforeQuery = false
        var descriptor: ParcelFileDescriptor? = null
        var rawDescriptor: FileDescriptor? = null
        val cursors = mutableListOf<Cursor>()

        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(URI, uri)
            assertEquals("w", mode)
            opens += 1
            if (this@BackupSaveFilenameFixture.mode == Mode.OPEN_FAILURE) throw FileNotFoundException(OPEN_ERROR)
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            ).also {
                descriptor = it
                rawDescriptor = it.fileDescriptor
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries += 1
            assertEquals(URI, uri)
            assertEquals(listOf(OpenableColumns.DISPLAY_NAME), projection?.toList())
            assertEquals(null, selection)
            assertEquals(null, selectionArgs)
            assertEquals(null, sortOrder)
            assertArrayEquals(PAYLOAD, file.readBytes())
            closedBeforeQuery = !checkNotNull(rawDescriptor).valid()
            assertTrue(closedBeforeQuery)
            when (mode) {
                Mode.SECURITY -> throw SecurityException(METADATA_ERROR)
                Mode.UNSUPPORTED -> throw UnsupportedOperationException(METADATA_ERROR)
                Mode.ILLEGAL_ARGUMENT -> throw IllegalArgumentException(METADATA_ERROR)
                Mode.RUNTIME -> throw RuntimeException(METADATA_ERROR)
                Mode.CANCELLATION -> throw CancellationException(METADATA_CANCELLED)
                Mode.NULL_CURSOR -> return null
                else -> Unit
            }
            val column = if (mode == Mode.MISSING_COLUMN) OpenableColumns.SIZE else OpenableColumns.DISPLAY_NAME
            return MatrixCursor(arrayOf(column)).also {
                if (mode != Mode.EMPTY_CURSOR) {
                    val value = when (mode) {
                        Mode.NULL_NAME -> null
                        Mode.BLANK_NAME -> " \t "
                        Mode.MISSING_COLUMN -> PAYLOAD.size
                        else -> chosenName
                    }
                    it.addRow(arrayOf(value))
                }
                cursors += it
            }
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("unused delete")
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("unused update")
    }

    private class SaveLauncher(private val selected: (String) -> Unit) : ActivityResultLauncher<String>() {
        override val contract: ActivityResultContract<String, *> = ActivityResultContracts.CreateDocument(
            "application/octet-stream",
        )
        override fun launch(input: String, options: ActivityOptionsCompat?) = selected(input)
        override fun unregister() = Unit
    }

    private class UnusedLauncher : ActivityResultLauncher<Array<String>>() {
        override val contract: ActivityResultContract<Array<String>, *> = ActivityResultContracts.OpenDocument()
        override fun launch(input: Array<String>, options: ActivityOptionsCompat?) = error("unused picker")
        override fun unregister() = Unit
    }

    enum class Mode {
        NAME,
        NULL_CURSOR,
        EMPTY_CURSOR,
        MISSING_COLUMN,
        NULL_NAME,
        BLANK_NAME,
        SECURITY,
        UNSUPPORTED,
        ILLEGAL_ARGUMENT,
        RUNTIME,
        CANCELLATION,
        OPEN_FAILURE,
    }

    companion object {
        const val CHOSEN_NAME = "Shift notes.mc1backup"
        const val OPEN_ERROR = "Synthetic backup output failed"
        const val METADATA_ERROR = "Synthetic document metadata unavailable"
        const val METADATA_CANCELLED = "Synthetic document metadata cancellation"
        private const val DEBUG_PACKAGE = "io.pocketmesh.app.debug"
        private const val TIMEOUT_MS = 5_000L
        private val URI = Uri.parse("content://one.meshcore.backup.fixture/document/opaque-id")
        private val PAYLOAD = byteArrayOf(0x31, 0x42, 0x53, 0x64)
    }
}
