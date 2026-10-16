package one.meshcore.shared.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.util.concurrent.TimeUnit

/**
 * Production [GeoHttpClient] for JVM (Android + desktop) over Ktor/OkHttp. Used by
 * [ElevationService] for Open-Meteo path samples.
 */
class KtorGeoHttpClient(private val client: HttpClient = geoHttpClient()) : GeoHttpClient {
    override suspend fun get(url: String): GeoHttpResponse {
        val response = client.get(url)
        return GeoHttpResponse(statusCode = response.status.value, body = response.bodyAsText())
    }
}

/** Shared geo GET client: redirects allowed (Open-Meteo is a fixed public API), short timeouts. */
fun geoHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = true
    expectSuccess = false
    engine {
        config {
            connectTimeout(GEO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            readTimeout(GEO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            callTimeout(GEO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }
}

private const val GEO_TIMEOUT_SECONDS = 15L
