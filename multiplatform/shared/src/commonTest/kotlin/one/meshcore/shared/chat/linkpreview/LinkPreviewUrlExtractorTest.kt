package one.meshcore.shared.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** URL selection for the fetch trigger: scheme filtering, trailing-punctuation, Giphy expand, mentions. */
class LinkPreviewUrlExtractorTest {
    @Test
    fun extractsFirstHttpUrlFromSurroundingText() {
        assertEquals(
            "https://example.com/article",
            LinkPreviewUrlExtractor.extractFirst("see https://example.com/article now"),
        )
    }

    @Test
    fun trimsTrailingSentencePunctuation() {
        assertEquals("https://example.com", LinkPreviewUrlExtractor.extractFirst("visit https://example.com."))
    }

    @Test
    fun expandsGiphyShortCodeWhenItIsTheWholeMessage() {
        assertEquals(
            "https://media.giphy.com/media/abc123/giphy.gif",
            LinkPreviewUrlExtractor.extractFirst("g:abc123"),
        )
    }

    @Test
    fun doesNotExpandGiphyShortCodeMixedWithOtherText() {
        // Not a whole-message short-code, and "g:abc" is not an http(s) URL, so nothing is eligible.
        assertNull(LinkPreviewUrlExtractor.extractFirst("look g:abc"))
    }

    @Test
    fun dropsUrlInsideAMentionToken() {
        // The link inside the @[...] mention is excluded; the bare link after it is the first eligible URL.
        assertEquals(
            "https://ok.com",
            LinkPreviewUrlExtractor.extractFirst("@[https://evil.com] and https://ok.com"),
        )
    }

    @Test
    fun returnsNullWhenNoLinkPresent() {
        assertNull(LinkPreviewUrlExtractor.extractFirst("no links in this message"))
    }

    @Test
    fun extractsEveryEligibleUrlInOrder() {
        assertEquals(
            listOf("https://a.com", "https://b.com"),
            LinkPreviewUrlExtractor.extractAll("https://a.com then https://b.com"),
        )
    }

    @Test
    fun extractsASchemeLessHostAsHttp() {
        assertEquals("http://example.com", LinkPreviewUrlExtractor.extractFirst("see example.com now"))
    }
}
