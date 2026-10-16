package one.meshcore.shared.map

/**
 * The HTTP seam the geo services fetch through. The project has no HTTP client dependency yet (only
 * DataStore's transitive okio), so [ElevationService] and any HTTP-backed geocoder depend on this
 * interface and tests supply a fake; a real Ktor/OkHttp actual is sequenced separately. Kept minimal:
 * a single GET returning status code and body, which is all Open-Meteo and a Nominatim fallback need.
 */
interface GeoHttpClient {
    /** Performs a GET and returns the response, or throws on transport failure (no connection, DNS, TLS). */
    suspend fun get(url: String): GeoHttpResponse
}

/** A minimal HTTP response: the status code and the decoded body text. */
data class GeoHttpResponse(val statusCode: Int, val body: String)
