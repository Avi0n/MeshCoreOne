package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** The hand-written http/https detector: scheme filtering, punctuation boundaries, balanced brackets. */
class UrlDetectorTest {
    @Test fun `detects http and https and drops other schemes`() {
        val text = "ftp://x.com and mailto:a@b.com then http://ok.com and https://sec.com"
        assertEquals(listOf("http://ok.com", "https://sec.com"), UrlDetector.detect(text).map { it.url })
    }

    @Test fun `excludes a trailing sentence period`() {
        assertEquals("http://example.com", UrlDetector.detect("visit http://example.com.").single().url)
    }

    @Test fun `excludes a trailing comma`() {
        assertEquals("https://example.com", UrlDetector.detect("see https://example.com, thanks").single().url)
    }

    @Test fun `keeps a balanced closing parenthesis`() {
        val url = "https://en.wikipedia.org/wiki/Foo_(bar)"
        assertEquals(url, UrlDetector.detect("read $url now").single().url)
    }

    @Test fun `trims an unbalanced closing parenthesis`() {
        assertEquals("https://ok.com", UrlDetector.detect("(see https://ok.com)").single().url)
    }

    @Test fun `range points at the matched url`() {
        val text = "go https://a.co/x here"
        val detected = UrlDetector.detect(text).single()
        assertEquals(detected.url, text.substring(detected.range.first, detected.range.last + 1))
    }

    @Test fun `infers http for a scheme-less host`() {
        val detected = UrlDetector.detect("see example.com here").single()
        assertEquals("http://example.com", detected.url)
        assertEquals("example.com", "see example.com here".substring(detected.range.first, detected.range.last + 1))
    }

    @Test fun `trims punctuation from a scheme-less host`() {
        assertEquals("http://example.com", UrlDetector.detect("visit example.com.").single().url)
    }

    @Test fun `keeps a scheme-less host path and fragment`() {
        assertEquals(
            "http://www.example.com/path#section",
            UrlDetector.detect("open www.example.com/path#section").single().url,
        )
    }

    @Test fun `does not double-detect a schemeful host as scheme-less`() {
        assertEquals(listOf("https://example.com"), UrlDetector.detect("https://example.com").map { it.url })
    }

    @Test fun `does not treat an email host or other-scheme host as a web link`() {
        assertEquals(emptyList(), UrlDetector.detect("write a@b.com or ftp://x.com").map { it.url })
    }
}
