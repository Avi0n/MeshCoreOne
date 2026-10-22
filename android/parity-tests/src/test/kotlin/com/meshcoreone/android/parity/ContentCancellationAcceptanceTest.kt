// PortedFrom: MC1/Services/InlineImageCache.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 The mounted native content consumer must preserve the admitted cancellation adaptation.
package com.meshcoreone.android.parity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCancellationAcceptanceTest : RoomProcessTest() {
    @Test fun callerTimeoutRemainsCancellationAndDoesNotPoisonInlineRetry() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
        val entered = CompletableDeferred<Unit>()
        var imageFetches = 0
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                if (url != "https://8.8.8.8/synthetic.png") return HttpFetchAttempt.Failed("Offline fixture")
                imageFetches++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val content = AppChatContent(context, store, http,
            DataStoreLinkPreviewPreferencesSource.create(storage.preferences, backgroundScope),
            storage.preferences, storage.preferences.snapshot(), backgroundScope)
        try {
            val result = async {
                try {
                    withTimeout(1_000) { content.inline("https://8.8.8.8/synthetic.png") }
                    error("Parked fetch must not complete")
                } catch (failure: TimeoutCancellationException) {
                    failure
                }
            }
            entered.await()
            assertTrue(result.await() is TimeoutCancellationException)
            val retry = async {
                try {
                    withTimeout(1_000) { content.inline("https://8.8.8.8/synthetic.png") }
                    error("Parked retry must not complete")
                } catch (failure: TimeoutCancellationException) {
                    failure
                }
            }
            assertTrue(retry.await() is TimeoutCancellationException)
            assertTrue("Cancellation must leave the URL retryable", imageFetches == 2)
        } finally {
            content.close()
            storage.close()
        }
    }
}
