package one.meshcore.shared.urlsafety

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Validates a URL before fetching to prevent SSRF via private/reserved network access, a verbatim
 * port of the Swift `URLSafetyChecker.isSafe`. Fails CLOSED: any parse failure, DNS failure, or
 * DNS timeout returns `false`.
 *
 * The link-preview fetcher calls [isSafe] on the initial URL and, because redirects are not
 * auto-followed (OkHttp `followRedirects(false)`), on every redirect `Location` before following
 * it. Relative `Location` values must be resolved against the current URL by the caller first;
 * this checker only inspects absolute URLs. Redirect hops are re-validated here but are NOT
 * re-checked against the malware filter (that gate runs once, up front, in the view model).
 */
object UrlSafetyChecker {
    /** Known-safe CDN hosts that bypass the SSRF checks (exact, case-sensitive match, per Swift). */
    val allowedHosts: Set<String> = setOf("media.giphy.com", "i.giphy.com")

    /** DNS resolution budget; a slower lookup fails closed. Mirrors the Swift 5-second timeout. */
    const val DNS_TIMEOUT_MS: Long = 5_000

    private val allowedSchemes = setOf("http", "https")

    /**
     * Returns `true` if [url] is safe to fetch. Rejects non-HTTP(S) schemes, host-less URLs, and
     * any URL whose host (as an IP literal or via [resolver] DNS lookup) is private/reserved.
     */
    suspend fun isSafe(url: String, resolver: HostResolver): Boolean {
        val host = httpHost(url) ?: return false
        if (host in allowedHosts) return true
        if (IpAddressClassifier.isPrivateOrReserved(host)) return false
        return resolveAndCheck(host, resolver)
    }

    /** HTTP(S) host, or null when the URL is missing, non-HTTP, or host-less. */
    private fun httpHost(url: String): String? {
        val components = UrlComponents.parse(url) ?: return null
        val scheme = components.scheme?.lowercase()
        if (scheme !in allowedSchemes) return null
        return components.host
    }

    /** Resolves [host] and returns `true` only if every resolved address is public. */
    private suspend fun resolveAndCheck(host: String, resolver: HostResolver): Boolean {
        val addresses = withTimeoutOrNull(DNS_TIMEOUT_MS) { resolver.resolve(host) }
        if (addresses.isNullOrEmpty()) return false
        return addresses.none { IpAddressClassifier.isPrivateOrReserved(it) }
    }
}
