package one.meshcore.shared.chat.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImagePixelSize
import one.meshcore.shared.chat.ImageURLClassifier
import one.meshcore.shared.chat.InlineImageCache
import one.meshcore.shared.chat.InlineImageHttpResult
import one.meshcore.shared.runThrowableResult
import one.meshcore.shared.urlsafety.HostResolver
import java.io.ByteArrayOutputStream

/** Bounded streaming GETs with every redirect checked before its response body is consumed. */
class KtorLinkContentFetcher(
    private val client: HttpClient,
    private val resolver: HostResolver,
    private val imageProcessor: PreviewImageProcessor = defaultPreviewImageProcessor(),
) : LinkContentFetcher {
    override suspend fun fetchHtml(url: String): String? = withResponse(url, upgradeToHTTPS = true) { response ->
        boundedBody(response, HTML_BYTE_CAP) { it.contains(HTML_MIME_SUBSTRING) }?.decodeToString()
    }

    override suspend fun fetchImageBytes(url: String): ByteArray? {
        val bytes = withResponse(url, upgradeToHTTPS = true) { response ->
            boundedBody(response, IMAGE_BYTE_CAP) { it.startsWith(IMAGE_MIME_PREFIX) }
        } ?: return null
        return imageProcessor.fit(bytes)
    }

    override suspend fun servesHtmlPage(url: String): Boolean = withResponse(url) { response ->
        response.status.isSuccess() && ImageURLClassifier.isHtmlContentType(response.contentType()?.toString())
    } ?: false

    override suspend fun probeImageDimensions(url: String): ImagePixelSize? =
        withResponse(url, range = PROBE_BYTE_RANGE) { response ->
            if (response.status != HttpStatusCode.OK &&
                response.status != HttpStatusCode.PartialContent
            ) {
                return@withResponse null
            }
            if (ImageURLClassifier.isHtmlContentType(response.contentType()?.toString())) return@withResponse null
            readBody(response, PROBE_BYTE_CAP)?.let(ImageDimensionReader::read)
        }

    override suspend fun fetchInlineImage(url: String): InlineImageHttpResult =
        withResponse<InlineImageHttpResult>(url) { response ->
            if (!response.status.isSuccess()) return@withResponse InlineImageHttpResult.Failed
            if (ImageURLClassifier.isHtmlContentType(response.contentType()?.toString())) {
                return@withResponse InlineImageHttpResult.HtmlPage
            }
            val bytes = readBody(response, InlineImageCache.MAX_DOWNLOAD_BYTES)
                ?: return@withResponse InlineImageHttpResult.Failed
            if (ImageDimensionReader.read(bytes) == null) return@withResponse InlineImageHttpResult.Failed
            InlineImageHttpResult.Image(bytes)
        } ?: InlineImageHttpResult.Failed

    private suspend fun <T> withResponse(
        url: String,
        upgradeToHTTPS: Boolean = false,
        range: String? = null,
        consume: suspend (HttpResponse) -> T,
    ): T? = runThrowableResult({ null }) {
        var result: T? = null
        val hopReader = RedirectHopReader { current ->
            client.prepareGet(current) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                range?.let { header(HttpHeaders.Range, it) }
            }.execute { response ->
                if (response.status.value in REDIRECT_STATUS_RANGE) {
                    response.headers[HttpHeaders.Location]
                } else {
                    // Consume this response before execute closes it; a second GET could return another redirect.
                    result = consume(response)
                    null
                }
            }
        }
        if (RedirectSafetyResolver.resolve(url, hopReader, resolver, upgradeToHTTPS) == null) null else result
    }

    private suspend fun boundedBody(
        response: HttpResponse,
        byteCap: Int,
        acceptsMime: (String) -> Boolean,
    ): ByteArray? {
        if (!isAcceptable(response, acceptsMime)) return null
        return readBody(response, byteCap)
    }

    private suspend fun readBody(response: HttpResponse, byteCap: Int): ByteArray? {
        val declaredLength = response.contentLength()
        if (declaredLength != null && declaredLength > byteCap) return null
        return readBounded(response.bodyAsChannel(), byteCap)
    }

    private fun isAcceptable(response: HttpResponse, acceptsMime: (String) -> Boolean): Boolean {
        if (!response.status.isSuccess()) return false
        val mime = response.contentType()?.let { "${it.contentType}/${it.contentSubtype}".lowercase() }
        return mime != null && acceptsMime(mime)
    }

    /** Streams [channel] into memory, returning null once the running total exceeds [byteCap]. */
    private suspend fun readBounded(channel: ByteReadChannel, byteCap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read > 0) {
                if (read > byteCap - out.size()) return null
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** Streaming ceiling for the HTML scrape GET (512 KiB), matching the Swift `htmlScrapeByteCap`. */
        const val HTML_BYTE_CAP: Int = 512 * 1024

        /** Streaming ceiling for the scraped-image GET (2 MiB), matching the Swift `imageByteCap`. */
        const val IMAGE_BYTE_CAP: Int = 2 * 1024 * 1024

        /** Sent on every request, matching the Swift `scrapeUserAgent`. */
        const val USER_AGENT: String = "MC1LinkPreview/1.0"

        const val PROBE_BYTE_CAP: Int = 1024 * 1024
        private const val PROBE_BYTE_RANGE = "bytes=0-65535"
        private const val HTML_MIME_SUBSTRING = "html"
        private const val IMAGE_MIME_PREFIX = "image/"
        private const val READ_CHUNK_BYTES = 16 * 1024
        private val REDIRECT_STATUS_RANGE = 300..399
    }
}
