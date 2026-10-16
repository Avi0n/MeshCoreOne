package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tap-dispatch classifier: a span URL to its typed [LinkTarget]. */
class LinkTargetTest {
    @Test fun `web urls parse to Web`() {
        assertEquals(LinkTarget.Web("https://apple.com"), LinkTarget.parse("https://apple.com"))
        assertEquals(LinkTarget.Web("http://x.com"), LinkTarget.parse("http://x.com"))
    }

    @Test fun `a map link parses to a coordinate`() {
        assertEquals(
            LinkTarget.Coordinate(1.5, -2.25),
            LinkTarget.parse("meshcore://map?lat=1.500000&lon=-2.250000"),
        )
    }

    @Test fun `contact and channel links parse to MeshCoreLink`() {
        assertTrue(LinkTarget.parse("meshcore://contact/add?name=Bob") is LinkTarget.MeshCoreLink)
        assertTrue(LinkTarget.parse("meshcore://channel/add?name=Cool") is LinkTarget.MeshCoreLink)
    }

    @Test fun `a mention link parses to Mention`() {
        assertEquals(LinkTarget.Mention("Bob"), LinkTarget.parse("meshcoreone://mention/Bob"))
    }

    @Test fun `a hashtag link parses to Hashtag`() {
        assertEquals(LinkTarget.Hashtag("weather"), LinkTarget.parse("meshcoreone://hashtag/weather"))
    }

    @Test fun `unknown schemes and hosts parse to null`() {
        assertNull(LinkTarget.parse("ftp://x.com"))
        assertNull(LinkTarget.parse("meshcore://status"))
        assertNull(LinkTarget.parse("meshcoreone://unknown/x"))
    }
}
