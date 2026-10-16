package one.meshcore.shared.chat.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import one.meshcore.shared.urlsafety.HostResolver
import java.util.Base64
import kotlin.coroutines.CoroutineContext

internal class LinkContentTestEngine(
    private val respond: suspend (HttpRequestData, CoroutineContext) -> HttpResponseData,
) : HttpClientEngineBase("link-content-test") {
    override val config = HttpClientEngineConfig()
    val requests = mutableListOf<HttpRequestData>()

    @OptIn(InternalAPI::class)
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        requests.add(data)
        return respond(data, callContext())
    }

    fun client() = HttpClient(this) {
        followRedirects = false
        expectSuccess = false
    }
}

internal fun contentResponse(
    context: CoroutineContext,
    body: ByteReadChannel = ByteReadChannel(LINK_CONTENT_PNG),
    status: HttpStatusCode = HttpStatusCode.OK,
    mime: String = "image/png",
    location: String? = null,
    length: Int? = null,
) = HttpResponseData(
    status,
    GMTDate(),
    Headers.build {
        append(HttpHeaders.ContentType, mime)
        location?.let { append(HttpHeaders.Location, it) }
        length?.let { append(HttpHeaders.ContentLength, it.toString()) }
    },
    HttpProtocolVersion.HTTP_1_1,
    body,
    context,
)

internal val LINK_CONTENT_RESOLVER = HostResolver { listOf("93.184.216.34") }
internal val LINK_CONTENT_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1XkAAAAASUVORK5CYII=",
)
