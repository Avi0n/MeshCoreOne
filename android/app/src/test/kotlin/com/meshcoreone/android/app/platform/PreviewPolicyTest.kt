// AndroidOnly: WP-407 Cached content never bypasses a changed master/scope permission.
package com.meshcoreone.android.app.platform

import com.meshcoreone.android.app.container.AppChatContent.PolicyPreviewPort
import com.meshcoreone.android.feature.chats.composer.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreviewPolicyTest {
    @Test fun masterAndScopeGateCachedAndAutomaticContentButOnlyMasterGatesExplicitLoading() = runTest {
        var enabled = true
        var automatic = true
        var cacheReads = 0
        var fetches = 0
        val data = LinkPreviewData("https://example.test/image.png", "Synthetic preview")
        val policy = PolicyPreviewPort(object : LinkPreviewPort {
            override suspend fun cachedPreview(url: String) = data.also { cacheReads++ }
            override suspend fun preview(url: String, isChannelMessage: Boolean) = LinkPreviewOutcome.Loaded(data).also { fetches++ }
            override suspend fun manualFetch(url: String) = LinkPreviewOutcome.Loaded(data).also { fetches++ }
        }, { enabled }, { automatic })
        assertEquals(data, policy.cachedPreview(data.url))
        automatic = false
        assertNull(policy.cachedPreview(data.url))
        assertEquals(LinkPreviewOutcome.Disabled, policy.preview(data.url, true))
        assertEquals(0, fetches)
        assertEquals(LinkPreviewOutcome.Loaded(data), policy.manualFetch(data.url))
        enabled = false
        automatic = true
        assertNull(policy.cachedPreview(data.url))
        assertEquals(LinkPreviewOutcome.NoPreviewAvailable, policy.preview(data.url, false))
        assertEquals(LinkPreviewOutcome.NoPreviewAvailable, policy.manualFetch(data.url))
        assertEquals(1, fetches)
        assertEquals(1, cacheReads)
    }
}
