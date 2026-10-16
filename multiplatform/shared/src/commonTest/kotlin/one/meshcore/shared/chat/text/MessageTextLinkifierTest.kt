package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The normalize -> tokenize -> resolve pipeline: display-string rewrites, exact span ranges, and the
 * overlap-priority resolution (contactShare > mention > url > hashtag > meshcoreLink > coordinate).
 */
class MessageTextLinkifierTest {
    @Test fun `plain text yields no spans and no coordinate`() {
        val result = MessageTextLinkifier.linkify("hello world", currentUserName = null)
        assertEquals("hello world", result.formatted.displayString)
        assertTrue(result.formatted.spans.isEmpty())
        assertNull(result.firstCoordinate)
    }

    @Test fun `a mention rewrites to a visible name with a mention span`() {
        val result = MessageTextLinkifier.linkify("hi @[Bob]!", currentUserName = null)
        assertEquals("hi @Bob!", result.formatted.displayString)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.MENTION, span.kind)
        assertEquals("@Bob", span.text(result))
        assertEquals("Bob", span.mentionName)
        assertEquals("meshcoreone://mention/Bob", span.url)
        assertTrue(!span.isSelfMention)
    }

    @Test fun `a mention of the local user is flagged self`() {
        val span = MessageTextLinkifier.linkify("@[me]", currentUserName = "ME").formatted.spans.single()
        assertTrue(span.isSelfMention)
    }

    @Test fun `a hashtag is styled and linked to the join deep link`() {
        val result = MessageTextLinkifier.linkify("join #weather now", currentUserName = null)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.HASHTAG, span.kind)
        assertEquals("#weather", span.text(result))
        assertEquals("meshcoreone://hashtag/weather", span.url)
    }

    @Test fun `a url shadows a hashtag fragment inside it`() {
        val result = MessageTextLinkifier.linkify("see http://x.com#frag", currentUserName = null)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.URL, span.kind)
        assertEquals("http://x.com#frag", span.text(result))
    }

    @Test fun `a hashtag inside a meshcore link wins over the link`() {
        val result = MessageTextLinkifier.linkify("meshcore://contact/add?name=Bob#team", currentUserName = null)
        assertTrue(result.formatted.spans.none { it.kind == LinkKind.MESHCORE_LINK })
        val hashtag = result.formatted.spans.single { it.kind == LinkKind.HASHTAG }
        assertEquals("#team", hashtag.text(result))
    }

    @Test fun `a mention shadows a url inside its name`() {
        val result = MessageTextLinkifier.linkify("@[http://x.com]", currentUserName = null)
        assertEquals("@http://x.com", result.formatted.displayString)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.MENTION, span.kind)
    }

    @Test fun `a contact-share token rewrites to a sanitized chip with an add-contact link`() {
        val hex = "AB".repeat(KEY_HEX_BYTES)
        val result = MessageTextLinkifier.linkify("card <$hex:1:Bob> here", currentUserName = null)
        assertEquals("card Bob here", result.formatted.displayString)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.CONTACT_SHARE, span.kind)
        assertEquals("Bob", span.text(result))
        assertTrue(span.url!!.startsWith("meshcore://contact/add"))
    }

    @Test fun `a contact-share token shadows a mention inside its name`() {
        val hex = "AB".repeat(KEY_HEX_BYTES)
        val result = MessageTextLinkifier.linkify("hi <$hex:1:@[Bob]>", currentUserName = null)
        assertTrue(result.formatted.spans.none { it.kind == LinkKind.MENTION })
        assertEquals(LinkKind.CONTACT_SHARE, result.formatted.spans.single().kind)
    }

    @Test fun `a coordinate is a span and drives the first coordinate`() {
        val result = MessageTextLinkifier.linkify("at 37.7749, -122.4194 now", currentUserName = null)
        val span = result.formatted.spans.single()
        assertEquals(LinkKind.COORDINATE, span.kind)
        assertEquals("37.7749, -122.4194", span.text(result))
        assertEquals("meshcore://map?lat=37.774900&lon=-122.419400", span.url)
        assertEquals(37.7749, result.firstCoordinate?.latitude)
    }

    private fun TextSpan.text(result: LinkifyResult): String =
        result.formatted.displayString.substring(start, endExclusive)

    private companion object {
        const val KEY_HEX_BYTES = 32
    }
}
