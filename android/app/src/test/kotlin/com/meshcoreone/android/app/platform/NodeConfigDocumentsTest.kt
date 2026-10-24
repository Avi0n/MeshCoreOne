// AndroidOnly: WP-318 JSON document adapter over real SAF result fencing; no physical provider evidence.
package com.meshcoreone.android.app.platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.feature.settings.app.backup.BackupDocumentException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], application = Application::class)
class NodeConfigDocumentsTest {
    private class Provider(val file: File) : ContentProvider() {
        var revoked = false
        var writeFailure = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            if (revoked) throw SecurityException("Synthetic revoked URI")
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).also { cursor ->
                cursor.addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else "synthetic.json" })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (revoked) throw SecurityException("Synthetic revoked URI")
            if (writeFailure && mode.contains('w')) throw FileNotFoundException("Synthetic write failure")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 1
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No insert expected")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No update expected")
    }

    private fun provider(): Provider {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Provider(File.createTempFile("wp318", ".json", context.cacheDir)).also {
            it.attachInfo(context, ProviderInfo().apply { authority = "wp318.synthetic" })
            ShadowContentResolver.registerProviderInternal("wp318.synthetic", it)
        }
    }
    @Test fun lateCancelledJsonPickerCannotCompleteTheNextRequest() = runTest {
        var launches = 0
        val backing = BackupDocuments(ApplicationProvider.getApplicationContext(), { launches++ }, { launches++ }, {})
        val documents = nodeConfigDocuments(backing)
        val first = launch { documents.pick() }
        yield()
        assertEquals(1, launches)
        first.cancelAndJoin()
        try { documents.pick(); fail("Pending result must stay fenced") }
        catch (_: BackupDocumentException.UriUnavailable) {}
        assertEquals(1, launches)
        backing.result(null)
        val next = async { documents.pick() }
        yield()
        backing.result(null)
        assertNull(next.await())
        backing.close()
    }

    @Test fun cancelledSaveDoesNotReportSuccessOrWriteAnything() = runTest {
        lateinit var backing: BackupDocuments
        backing = BackupDocuments(ApplicationProvider.getApplicationContext(), { backing.result(null) }, {}, {})
        assertFalse(nodeConfigDocuments(backing).save("synthetic.json", "{}".toByteArray()))
        assertNull(backing.savedUri)
        backing.close()
    }

    @Test fun revokedAndFailedOutputAreExplicitFailuresNotSaved() = runTest {
        val provider = provider()
        provider.revoked = true
        lateinit var backing: BackupDocuments
        backing = BackupDocuments(ApplicationProvider.getApplicationContext(),
            { backing.result(Uri.parse("content://wp318.synthetic/config")) }, {}, {})
        try {
            try { nodeConfigDocuments(backing).save("synthetic.json", "{}".toByteArray()); fail("Revocation must surface") }
            catch (_: BackupDocumentException.PermissionRevoked) {}
            provider.revoked = false
            provider.writeFailure = true
            try { nodeConfigDocuments(backing).save("synthetic.json", "{}".toByteArray()); fail("Write failure must surface") }
            catch (_: BackupDocumentException.WriteFailed) {}
            assertNull(backing.savedUri)
        } finally { backing.close(); provider.file.delete() }
    }

    @Test fun jsonRoundTripUsesTheSelectedProviderWithoutBackupEnvelope() = runTest {
        val provider = provider()
        val uri = Uri.parse("content://wp318.synthetic/config")
        lateinit var backing: BackupDocuments
        backing = BackupDocuments(ApplicationProvider.getApplicationContext(),
            { backing.result(uri) }, { backing.result(uri) }, {})
        try {
            val documents = nodeConfigDocuments(backing)
            val json = """{"name":"Synthetic JSON"}""".toByteArray()
            assertTrue(documents.save("synthetic.json", json))
            assertArrayEquals(json, documents.pick())
            assertArrayEquals(json, provider.file.readBytes())
            assertEquals(uri, backing.savedUri)
        } finally { backing.close(); provider.file.delete() }
    }
}
