// AndroidOnly: WP-407 Preserve caller cancellation and explicit content deadlines through the real process image cache.
package com.meshcoreone.android.app.platform

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ChatImage
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InlineContentCancellationTest : RoomProcessTest() {
    @Test fun callerTimeoutPropagatesAndTheSameUrlCanBeRetried() = runTest {
        val image = png()
        var attempts = 0
        var closed = 0
        val http = fetching {
            if (++attempts == 1) awaitCancellation()
            response(image) { closed++ }
        }
        withContent(http) { content, _, _ ->
            val started = testScheduler.currentTime
            try {
                withTimeout(1_000) { content.inline(URL) }
                fail("The caller timeout must cancel the fetch")
            } catch (_: TimeoutCancellationException) {
                assertEquals(1_000L, testScheduler.currentTime - started)
            }
            val loaded = content.inline(URL)
            assertTrue(loaded is ChatImage.Decoded)
            assertArrayEquals(image, content.shareData(URL))
            assertEquals(2, attempts)
            assertEquals(1, closed)
            assertSame((loaded as ChatImage.Decoded).handle, (content.inline(URL) as ChatImage.Decoded).handle)
            assertEquals(2, attempts)
        }
    }

    @Test fun plainCancellationRetainsItsIdentityAndDoesNotNegativeCacheTheUrl() = runTest {
        val cancellation = CancellationException("Synthetic caller cancellation")
        var attempts = 0
        val http = fetching {
            if (++attempts == 1) throw cancellation
            response(png()) {}
        }
        withContent(http) { content, _, _ ->
            try {
                content.inline(URL)
                fail("Cancellation must not become an image result")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
                // Coroutine stack recovery may copy the exception, retaining the original cause.
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
            }
            assertTrue(content.inline(URL) is ChatImage.Decoded)
            assertEquals(2, attempts)
        }
    }

    @Test fun onlyTheInternalDeadlineBecomesAnExplicitTimeoutAndRemainsRetryable() = runTest {
        var attempts = 0
        val http = fetching {
            if (++attempts == 1) awaitCancellation()
            response(png()) {}
        }
        withContent(http) { content, _, _ ->
            val started = testScheduler.currentTime
            try {
                content.inline(URL)
                fail("The internal deadline must fail explicitly")
            } catch (actual: IllegalStateException) {
                assertEquals("Inline image fetch timed out", actual.message)
                assertEquals(30_000L, testScheduler.currentTime - started)
            }
            assertTrue(content.inline(URL) is ChatImage.Decoded)
            assertEquals(2, attempts)
        }
    }

    @Test fun observerCleanupFinishesWhileItsRealPreferencesAreStillOpen() = runTest {
        val started = CompletableDeferred<Unit>()
        var observerStopped = false
        withContent(fetching { response(png()) {} }) { content, scope, storage ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    storage.preferences.snapshots.collect {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                } finally {
                    withContext(NonCancellable) {
                        assertTrue(storage.preferences.get(AppStorageKey.linkPreviewsEnabled))
                        observerStopped = true
                    }
                }
            }
            started.await()
            assertTrue(content.inline(URL) is ChatImage.Decoded)
        }
        assertTrue(observerStopped)
    }

    private suspend fun TestScope.withContent(
        http: BoundedHttpFetching,
        block: suspend (AppChatContent, CoroutineScope, MeshCoreStorage) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val lifetime = SupervisorJob(requireNotNull(backgroundScope.coroutineContext[Job]))
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        var content: AppChatContent? = null
        try {
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            val ownedContent = AppChatContent(
                context, store, http,
                DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
                storage.preferences, storage.preferences.snapshot(), scope,
            )
            content = ownedContent
            block(ownedContent, scope, storage)
        } finally {
            withContext(NonCancellable) {
                try {
                    lifetime.cancelAndJoin()
                } finally {
                    try { content?.close() } finally { storage.close() }
                }
            }
        }
    }

    private fun fetching(fetch: suspend () -> HttpFetchAttempt) = object : BoundedHttpFetching {
        override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
            assertEquals(URL, url)
            return fetch()
        }
    }

    private fun response(data: ByteArray, closed: () -> Unit) =
        HttpFetchAttempt.Started(200, "image/png", data.size.toLong(), closed) { receive -> receive(data) }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    companion object {
        private const val URL = "https://8.8.8.8/synthetic-cancellation.png"
    }
}
