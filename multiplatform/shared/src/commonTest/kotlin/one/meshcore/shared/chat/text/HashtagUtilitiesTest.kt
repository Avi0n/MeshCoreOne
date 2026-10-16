package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Detection and normalization rules for hashtag channels, ported from the iOS `HashtagUtilities`. */
class HashtagUtilitiesTest {
    @Test fun `normalizeHashtagName lowercases and drops the hash`() {
        assertEquals("general", HashtagUtilities.normalizeHashtagName("#General"))
        assertEquals("general", HashtagUtilities.normalizeHashtagName("General"))
    }

    @Test fun `isValidHashtagName accepts alphanumeric and hyphen but not leading hyphen`() {
        assertTrue(HashtagUtilities.isValidHashtagName("general"))
        assertTrue(HashtagUtilities.isValidHashtagName("weather-sf"))
        assertTrue(HashtagUtilities.isValidHashtagName("1abc"))
        assertFalse(HashtagUtilities.isValidHashtagName("-bad"))
        assertFalse(HashtagUtilities.isValidHashtagName("bad_char"))
        assertFalse(HashtagUtilities.isValidHashtagName(""))
    }

    @Test fun `sanitizeHashtagNameInput lowercases keeps alphanumeric hyphen and strips leading hyphens`() {
        assertEquals("hello-world", HashtagUtilities.sanitizeHashtagNameInput("--Hello-World!"))
        assertEquals("abc", HashtagUtilities.sanitizeHashtagNameInput("a b c"))
    }

    @Test fun `extractHashtags finds simple hashtags`() {
        val tags = HashtagUtilities.extractHashtags("join #weather and #news", emptyList())
        assertEquals(listOf("#weather", "#news"), tags.map { it.name })
    }

    @Test fun `extractHashtags skips a fragment inside a url`() {
        val text = "visit http://example.com#section and #real"
        val urlRanges = UrlDetector.detect(text).map { it.range }
        val tags = HashtagUtilities.extractHashtags(text, urlRanges)
        assertEquals(listOf("#real"), tags.map { it.name })
    }

    @Test fun `extractHashtags stops at the first non-ascii character`() {
        // The pattern is ASCII-only, mirroring iOS; a following unicode letter ends the match.
        val tags = HashtagUtilities.extractHashtags("tag #abc日本 here", emptyList())
        assertEquals(listOf("#abc"), tags.map { it.name })
    }

    @Test fun `extractHashtags returns the exact char range`() {
        val text = "hi #tag"
        val tag = HashtagUtilities.extractHashtags(text, emptyList()).single()
        assertEquals("#tag", text.substring(tag.range.first, tag.range.last + 1))
    }
}
