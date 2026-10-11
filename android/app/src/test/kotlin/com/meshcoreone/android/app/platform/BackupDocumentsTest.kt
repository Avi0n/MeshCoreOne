// AndroidOnly: WP-407 SAF picker isolation and provider failures; no real device/provider acceptance claim.
package com.meshcoreone.android.app.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.feature.settings.app.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class BackupDocumentsTest {
    @Test fun aCancelledPickerCannotAnswerANewerOperationWithItsLateResult() = runTest {
        var launches = 0
        val documents = BackupDocuments(ApplicationProvider.getApplicationContext(), { launches++ }, { launches++ })
        val pending = launch { documents.pick() }
        yield()
        assertEquals(1, launches)
        pending.cancelAndJoin()
        try { documents.pick(); fail("Late picker result must stay isolated") }
        catch (_: BackupDocumentException.UriUnavailable) {}
        assertEquals(1, launches)
        documents.result(null)
        val next = async { documents.pick() }
        yield()
        assertEquals(2, launches)
        documents.result(null)
        assertEquals(BackupPickOutcome.Cancelled, next.await())
        documents.close()
    }

    @Test fun revokedAndMalformedProviderMetadataAreTypedFailures() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var revoked = true
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (revoked) throw SecurityException("Synthetic revoked URI")
                throw IllegalArgumentException("Synthetic malformed provider metadata")
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No write expected")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No write expected")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No write expected")
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "wp407.synthetic" })
        ShadowContentResolver.registerProviderInternal("wp407.synthetic", provider)
        lateinit var documents: BackupDocuments
        documents = BackupDocuments(context, {}, { documents.result(Uri.parse("content://wp407.synthetic/backup")) })
        try { documents.pick(); fail("Revocation must surface") }
        catch (_: BackupDocumentException.PermissionRevoked) {}
        revoked = false
        try { documents.pick(); fail("Provider error must surface") }
        catch (expected: BackupDocumentException.UriUnavailable) {
            assertTrue(generateSequence<Throwable>(expected) { it.cause }.any { it is IllegalArgumentException })
        }
        documents.close()
    }
}
