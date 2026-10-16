package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.urlsafety.HostResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Manual redirect re-validation: every resolved hop is SSRF-checked; a private target is refused. */
class RedirectSafetyResolverTest {
    private fun resolver(privateHosts: Set<String> = emptySet()) = HostResolver { host ->
        if (host in privateHosts) listOf("10.0.0.1") else listOf("93.184.216.34")
    }

    /** A hop reader over a fixed url→Location map; a missing key is a terminal (non-redirect) response. */
    private fun hops(vararg pairs: Pair<String, String?>) = RedirectHopReader { url -> pairs.toMap()[url] }

    @Test
    fun terminalUrlIsReturnedWhenNoRedirect() = runTest {
        val result = RedirectSafetyResolver.resolve(
            startUrl = "https://example.com/page",
            hopReader = hops(),
            resolver = resolver(),
        )
        assertEquals("https://example.com/page", result)
    }

    @Test
    fun followsSafeRedirectToTerminal() = runTest {
        val result = RedirectSafetyResolver.resolve(
            startUrl = "https://example.com/a",
            hopReader = hops("https://example.com/a" to "https://cdn.example.com/b"),
            resolver = resolver(),
        )
        assertEquals("https://cdn.example.com/b", result)
    }

    @Test
    fun refusesRedirectToPrivateHost() = runTest {
        val result = RedirectSafetyResolver.resolve(
            startUrl = "https://example.com/a",
            hopReader = hops("https://example.com/a" to "https://intranet.local/secret"),
            resolver = resolver(privateHosts = setOf("intranet.local")),
        )
        assertNull(result)
    }

    @Test
    fun resolvesRelativeLocationAgainstCurrentUrl() = runTest {
        val result = RedirectSafetyResolver.resolve(
            startUrl = "https://example.com/page",
            hopReader = hops("https://example.com/page" to "/final"),
            resolver = resolver(),
        )
        assertEquals("https://example.com/final", result)
    }

    @Test
    fun refusesAChainLongerThanTheCap() = runTest {
        var hopCount = 0
        val looping = RedirectHopReader { "https://example.com/hop-${++hopCount}" }
        val result = RedirectSafetyResolver.resolve("https://example.com/start", looping, resolver())
        assertNull(result)
        assertEquals(RedirectSafetyResolver.MAX_REDIRECTS, hopCount)
    }

    @Test
    fun malformedInitialUrlsCannotAcquireTheBuildersDefaultOrigin() = runTest {
        for (url in listOf("/image.png", "https://", "file:///image.png", "data:image/png;base64,AAAA")) {
            var hopCount = 0
            val reader = RedirectHopReader {
                hopCount++
                null
            }
            assertNull(RedirectSafetyResolver.resolve(url, reader, resolver()))
            assertEquals(0, hopCount)
        }
    }
}
