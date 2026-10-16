package one.meshcore.shared.chat.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Ports the iOS `MentionDeeplinkSupportTests` — the `meshcoreone://mention/<name>` round trip. */
class MentionDeeplinkTest {
    @Test fun `a plain name round-trips`() {
        val url = MentionDeeplink.url("Alice Smith")
        assertNotNull(url)
        val parts = parseMeshLink(url)
        assertEquals(MentionDeeplink.SCHEME, parts?.scheme)
        assertEquals(MentionDeeplink.HOST, parts?.host)
        assertEquals("Alice Smith", MentionDeeplink.name(url))
    }

    @Test fun `a name with a slash round-trips without losing the prefix`() {
        val original = "Node 1/2 Repeater"
        assertEquals(original, MentionDeeplink.name(MentionDeeplink.url(original)!!))
    }

    @Test fun `a name with a percent sign round-trips`() {
        val original = "100% Coverage"
        assertEquals(original, MentionDeeplink.name(MentionDeeplink.url(original)!!))
    }

    @Test fun `a literal percent-escape is not double-decoded`() {
        val original = "a%2Fb"
        assertEquals(original, MentionDeeplink.name(MentionDeeplink.url(original)!!))
    }

    @Test fun `a unicode name round-trips`() {
        val original = "Café 北京"
        assertEquals(original, MentionDeeplink.name(MentionDeeplink.url(original)!!))
    }

    @Test fun `name returns null for non-mention urls`() {
        assertNull(MentionDeeplink.name("https://apple.com"))
        assertNull(MentionDeeplink.name("meshcore://map?lat=1&lon=2"))
        assertNull(MentionDeeplink.name("meshcoreone://hashtag/general"))
    }
}
