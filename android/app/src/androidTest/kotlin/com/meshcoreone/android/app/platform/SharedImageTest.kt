// AndroidOnly: WP-407 Real Android filesystem/provider assertions; Windows Robolectric paths are not Android paths.
package com.meshcoreone.android.app.platform

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedImageTest {
    @Test fun sharingRetainsImageBytesButDoesNotExposeOtherPrivateFiles() = runBlocking {
        val activity = InstrumentationRegistry.getInstrumentation().targetContext
            val bytes = "GIF89a-synthetic-share-bytes".toByteArray()
            val intent = sharedImageIntent(activity, bytes)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = requireNotNull(intent.clipData).getItemAt(0).uri
            assertEquals("content", uri.scheme)
            assertEquals("${activity.packageName}.shared-images", uri.authority)
            activity.contentResolver.openInputStream(uri).use { assertArrayEquals(bytes, requireNotNull(it).readBytes()) }
            val secret = File(activity.filesDir, "synthetic-private-key").apply { writeText("not shared") }
            try {
                FileProvider.getUriForFile(activity, "${activity.packageName}.shared-images", secret)
                fail("Private files must not be exposed")
            } catch (_: IllegalArgumentException) {}
            assertTrue(secret.delete())
    }
}
