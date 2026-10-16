package one.meshcore.shared.chat.linkpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Ksoup Open Graph / Twitter Card scrape: priority, resolution, entity decode, and drops. */
class HtmlMetadataParserTest {
    private val base = "https://example.com/page"

    private fun parse(head: String) = HtmlMetadataParser.parse("<html><head>$head</head><body></body></html>", base)

    @Test
    fun ogImageWinsOverTwitterImage() {
        val result = parse(
            """
            <meta property="twitter:image" content="https://example.com/twitter.png">
            <meta property="og:image" content="https://example.com/og.png">
            <meta property="og:title" content="Title">
            """.trimIndent(),
        )
        assertEquals("https://example.com/og.png", result?.imageUrl)
    }

    @Test
    fun attributeOrderIsIndependent() {
        // content before property, name instead of property.
        val result = parse("""<meta content="https://example.com/og.png" name="og:image">""")
        assertEquals("https://example.com/og.png", result?.imageUrl)
    }

    @Test
    fun relativeImageResolvesAgainstBase() {
        val result = parse("""<meta property="og:image" content="/hero.png">""")
        assertEquals("https://example.com/hero.png", result?.imageUrl)
    }

    @Test
    fun htmlEntitiesInTitleAreDecoded() {
        val result = parse("""<meta property="og:title" content="Tom &amp; Jerry &lt;3">""")
        assertEquals("Tom & Jerry <3", result?.title)
    }

    @Test
    fun nonHttpImageIsDropped() {
        val result = parse(
            """
            <meta property="og:image" content="file:///etc/passwd">
            <meta property="og:title" content="Title">
            """.trimIndent(),
        )
        assertNull(result?.imageUrl)
        assertEquals("Title", result?.title)
    }

    @Test
    fun titleOnlyPageStillReturns() {
        val result = parse("""<meta property="og:title" content="Just a title">""")
        assertEquals("Just a title", result?.title)
        assertNull(result?.imageUrl)
    }

    @Test
    fun titleFallsBackToTitleTagThenSiteName() {
        assertEquals("Doc Title", parse("<title>Doc Title</title>")?.title)
        assertEquals("Site", parse("""<meta property="og:site_name" content="Site">""")?.title)
    }

    @Test
    fun faviconIsExtractedAndResolved() {
        val result = parse(
            """
            <title>Doc</title>
            <link rel="icon" href="/favicon.ico">
            """.trimIndent(),
        )
        assertEquals("https://example.com/favicon.ico", result?.iconUrl)
    }

    @Test
    fun pageWithNeitherTitleNorImageReturnsNull() {
        assertNull(HtmlMetadataParser.parse("<html><head></head><body>hi</body></html>", base))
    }
}
