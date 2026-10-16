package one.meshcore.shared.chat.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * Builds the OkHttp-backed [HttpClient] the [KtorLinkContentFetcher] fetches through. Auto-follow is
 * disabled at both the Ktor and OkHttp layers so [RedirectSafetyResolver] drives every hop through the SSRF
 * gate (a following client would fetch a redirect target before it could be re-validated). Timeouts match
 * the Swift 5-second scrape/image ceilings, and `expectSuccess` stays off so the fetcher inspects the status
 * itself rather than the client throwing.
 */
fun linkPreviewHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}

/** The Swift `htmlScrapeTimeout`/`imageFetchTimeout` ceiling, applied per request. */
private const val REQUEST_TIMEOUT_SECONDS = 5L
