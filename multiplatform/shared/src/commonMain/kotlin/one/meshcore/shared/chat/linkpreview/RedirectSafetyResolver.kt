package one.meshcore.shared.chat.linkpreview

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import one.meshcore.shared.urlsafety.HostResolver
import one.meshcore.shared.urlsafety.UrlSafetyChecker

/**
 * Reads one HTTP hop WITHOUT following it — the Ktor `followRedirects(false)` primitive the
 * [RedirectSafetyResolver] drives. The production adapter issues the request and returns the raw `Location`
 * header of a 3xx response (or null for a terminal, non-redirect response); tests supply a fake.
 */
fun interface RedirectHopReader {
    /** The raw `Location` header for [url]'s response, or null if the response is not a redirect. */
    suspend fun redirectLocation(url: String): String?
}

/** Validates the initial URL and every redirect before the non-following reader requests them. */
object RedirectSafetyResolver {
    /** Redirect-chain cap, matching `URLSession`'s default ceiling; a longer chain is refused. */
    const val MAX_REDIRECTS: Int = 16

    /** Preview requests upgrade every hop to HTTPS; direct inline images preserve their transport. */
    suspend fun resolve(
        startUrl: String,
        hopReader: RedirectHopReader,
        resolver: HostResolver,
        upgradeToHTTPS: Boolean = false,
    ): String? {
        var current = normalize(startUrl, upgradeToHTTPS) ?: return null
        if (!UrlSafetyChecker.isSafe(current, resolver)) return null
        repeat(MAX_REDIRECTS) {
            when (val hop = nextHop(current, hopReader, resolver, upgradeToHTTPS)) {
                is Hop.Finished -> return hop.url
                is Hop.Follow -> current = hop.url
            }
        }
        return null
    }

    private suspend fun nextHop(
        current: String,
        hopReader: RedirectHopReader,
        resolver: HostResolver,
        upgradeToHTTPS: Boolean,
    ): Hop {
        val location = hopReader.redirectLocation(current) ?: return Hop.Finished(current)
        val absolute = resolveRelative(base = current, location = location) ?: return Hop.Finished(null)
        val destination = normalize(absolute, upgradeToHTTPS) ?: return Hop.Finished(null)
        if (destination == current || !UrlSafetyChecker.isSafe(destination, resolver)) return Hop.Finished(null)
        return Hop.Follow(destination)
    }

    private fun normalize(url: String, upgradeToHTTPS: Boolean): String? = runCatching {
        val builder = URLBuilder(url)
        if (builder.host.isEmpty() ||
            (builder.protocolOrNull != URLProtocol.HTTP && builder.protocolOrNull != URLProtocol.HTTPS)
        ) {
            return@runCatching null
        }
        if (upgradeToHTTPS && (builder.protocol == URLProtocol.HTTP || builder.protocol == URLProtocol.HTTPS)) {
            builder.protocol = URLProtocol.HTTPS
            if (builder.port == URLProtocol.HTTP.defaultPort) builder.port = DEFAULT_PORT
        }
        builder.buildString()
    }.getOrNull()

    /** Resolves a possibly-relative `Location` against [base] into an absolute URL, or null if unparseable. */
    private fun resolveRelative(base: String, location: String): String? =
        runCatching { URLBuilder(base).takeFrom(location).buildString() }.getOrNull()

    private sealed interface Hop {
        data class Follow(val url: String) : Hop

        data class Finished(val url: String?) : Hop
    }
}
