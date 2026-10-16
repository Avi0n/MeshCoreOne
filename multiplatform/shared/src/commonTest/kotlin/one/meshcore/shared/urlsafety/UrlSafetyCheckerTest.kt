package one.meshcore.shared.urlsafety

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SSRF control-flow vectors for [UrlSafetyChecker], mirroring the Swift `URLSafetyCheckerTests`
 * scheme/host/allowlist/IP-literal cases and adding the DNS-resolution and redirect-hop paths that
 * were exercised indirectly in Swift (loadImageData redirect-to-private, InlineImageCache probes).
 * A fake [HostResolver] stands in for real DNS so every branch is deterministic.
 */
class UrlSafetyCheckerTest {
    /** Resolver returning a fixed address set, or `null` to simulate a lookup failure. */
    private fun resolverOf(vararg addresses: String): HostResolver = HostResolver { addresses.toList() }

    private val failingResolver = HostResolver { null }
    private val unreachedResolver = HostResolver { error("resolver must not be called") }

    // MARK: - Scheme validation

    @Test
    fun allowsHttpsAndHttp() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://example.com/page", resolverOf("93.184.216.34")))
        assertTrue(UrlSafetyChecker.isSafe("http://example.com/page", resolverOf("93.184.216.34")))
    }

    @Test
    fun rejectsNonHttpSchemes() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("ftp://example.com/file", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("file:///etc/passwd", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("javascript:alert(1)", unreachedResolver))
    }

    @Test
    fun rejectsUrlWithNoHost() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("https://", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("https:///path-only", unreachedResolver))
    }

    // MARK: - Allow-listed hosts

    @Test
    fun allowsGiphyHostsWithoutResolving() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://media.giphy.com/media/abc/giphy.gif", unreachedResolver))
        assertTrue(UrlSafetyChecker.isSafe("https://i.giphy.com/media/abc/giphy.gif", unreachedResolver))
    }

    // MARK: - IP-literal hosts

    @Test
    fun rejectsPrivateIpLiterals() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("http://192.168.1.1/admin", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("http://127.0.0.1:8080/api", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("http://169.254.169.254/latest/meta-data/", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("http://10.0.0.1:3000/api", unreachedResolver))
        assertFalse(UrlSafetyChecker.isSafe("http://[::1]:8080/api", unreachedResolver))
    }

    @Test
    fun allowsPublicIpLiteral() = runTest {
        // A public IP literal is not short-circuited (matching Swift): it still resolves, and the
        // JVM resolver returns the literal unchanged, which the classifier confirms is public.
        assertTrue(UrlSafetyChecker.isSafe("https://8.8.8.8/", resolverOf("8.8.8.8")))
    }

    // MARK: - DNS resolution

    @Test
    fun allowsHostResolvingToPublicAddresses() = runTest {
        assertTrue(UrlSafetyChecker.isSafe("https://example.com/", resolverOf("93.184.216.34", "2606:2800::1")))
    }

    @Test
    fun rejectsHostResolvingToAnyPrivateAddress() = runTest {
        // One poisoned record among public ones must fail the whole host (DNS-rebinding guard).
        assertFalse(UrlSafetyChecker.isSafe("https://rebind.example/", resolverOf("93.184.216.34", "127.0.0.1")))
        assertFalse(UrlSafetyChecker.isSafe("https://internal.example/", resolverOf("169.254.169.254")))
    }

    @Test
    fun failsClosedOnResolutionFailure() = runTest {
        assertFalse(UrlSafetyChecker.isSafe("https://nxdomain.example/", failingResolver))
        assertFalse(UrlSafetyChecker.isSafe("https://empty.example/", resolverOf()))
    }

    @Test
    fun failsClosedOnResolutionTimeout() = runTest {
        val slowResolver = HostResolver {
            delay(UrlSafetyChecker.DNS_TIMEOUT_MS + 1_000)
            listOf("93.184.216.34")
        }
        assertFalse(UrlSafetyChecker.isSafe("https://slow.example/", slowResolver))
    }

    // MARK: - Redirect-hop re-validation (same entry point the W3-S7 fetcher calls per hop)

    @Test
    fun revalidatesRedirectLocationPerHop() = runTest {
        val publicHop = "https://cdn.example.com/image.png"
        val privateHop = "http://169.254.169.254/latest/meta-data/"
        assertTrue(UrlSafetyChecker.isSafe(publicHop, resolverOf("93.184.216.34")))
        assertFalse(UrlSafetyChecker.isSafe(privateHop, unreachedResolver))
    }

    // MARK: - Host parsing edge cases (fail closed)

    @Test
    fun stripsUserInfoBeforeHostCheck() = runTest {
        // Credentials before '@' must not smuggle a private host past the check.
        assertFalse(UrlSafetyChecker.isSafe("http://public.example@127.0.0.1/", unreachedResolver))
    }
}
