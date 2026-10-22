// PortedFrom: MC1Tests/Views/Chats/Components/MessageTextTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Components/MessageTextContactShareTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Utilities/ChatCoordinateDetectorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParser
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import java.net.URI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MessageTextTest {
    private val key = Bytes(ByteArray(32) { 0xAB.toByte() })
    private val contactName = "Field Base"

    private fun fmt(text: String) = MessageTextFormatter.format(text)
    private fun token(name: String) = ContactShare.formatShare(key, ContactType.CHAT, name)
    private fun contactUrl(name: String) = ContactShare.exportContactUri(name, key, ContactType.CHAT)
    private fun FormattedMessage.hasContactLink() = tokens.any { it.url?.let { url -> URI(url).host } == "contact" }
    private fun FormattedMessage.linkedText(url: String): String? =
        tokens.lastOrNull { it.url == url }?.let { text.substring(it.start, it.end) }
    private fun FormattedMessage.tokenFor(substring: String): LinkToken? {
        val start = text.indexOf(substring)
        return tokens.firstOrNull { it.start <= start && start + substring.length <= it.end }
    }

    @Test
    @OriginalCase("MessageTextTests::URL-like text in mention should carry the mention link, not a parsed URL()")
    fun `URL-like text in mention carries the mention link`() {
        val formatted = fmt("Hey @[Ferret PocketMesh WCMesh.com], check this out!")
        val link = formatted.linkFor("WCMesh.com")!!
        assertTrue(link.startsWith("meshcoreone://mention/"))
        assertTrue(formatted.tokenFor("WCMesh.com")!!.underline, "WCMesh.com should have mention styling (underline)")
    }

    @Test
    @OriginalCase("MessageTextTests::URL-like text in mention with IP address should carry the mention link, not a parsed URL()")
    fun `IP address inside a mention carries the mention link`() {
        val link = fmt("Message from @[Node 192.168.1.100]").linkFor("192.168.1.100")!!
        assertTrue(link.startsWith("meshcoreone://mention/"))
    }

    @Test
    @OriginalCase("MessageTextTests::Regular URL outside mention should still be parsed as link()")
    fun `regular URL outside a mention is parsed as a link`() {
        assertEquals("https://example.com", fmt("Check https://example.com for details").linkFor("https://example.com"))
    }

    @Test
    @OriginalCase("MessageTextTests::Message with both mention containing URL-like text and real URL()")
    fun `mention containing URL-like text and a real URL`() {
        val formatted = fmt("@[Server node.example.com] says check https://docs.example.com")
        assertTrue(formatted.linkFor("node.example.com")!!.startsWith("meshcoreone://mention/"))
        assertEquals("https://docs.example.com", formatted.linkFor("https://docs.example.com"))
    }

    @Test
    @OriginalCase("MessageTextTests::A simple mention carries a meshcoreone://mention/<percent-encoded-name> link()")
    fun `a simple mention carries a percent-encoded mention link`() {
        val link = fmt("Hey @[Alice Smith], how are you?").linkFor("@Alice Smith")!!
        assertEquals("meshcoreone://mention/Alice%20Smith", link)
        assertEquals("Alice Smith", MentionDeeplink.name(link))
    }

    @Test
    @OriginalCase("MessageTextTests::A decimal coordinate pair is linkified as a meshcore map link()")
    fun `a decimal coordinate pair is a map link`() {
        val url = URI(fmt("Meet at 37.334900, -122.009020 tonight").linkFor("37.334900, -122.009020")!!)
        assertEquals("meshcore", url.scheme)
        assertEquals("map", url.host)
    }

    @Test
    @OriginalCase("MessageTextTests::An integer pair is not linkified()")
    fun `an integer pair is not linkified`() = assertNull(fmt("ratio is 3, 4 today").linkFor("3, 4"))

    @Test
    @OriginalCase("MessageTextTests::An out-of-range pair is not linkified()")
    fun `an out-of-range pair is not linkified`() = assertNull(fmt("bad 200.0, 400.0 coord").linkFor("200.0, 400.0"))

    @Test
    @OriginalCase("MessageTextTests::A coordinate embedded in text linkifies only the coordinate substring()")
    fun `only the coordinate substring is linked`() {
        val formatted = fmt("here: 37.7749, -122.4194 ok")
        assertEquals("map", URI(formatted.linkFor("37.7749, -122.4194")!!).host)
        assertNull(formatted.linkFor("here"))
        assertNull(formatted.linkFor("ok"))
    }

    @Test
    @OriginalCase("MessageTextTests::Multiple coordinates in one message each linkify()")
    fun `multiple coordinates each linkify`() {
        val formatted = fmt("A 10.0, 20.0 and B 30.0, 40.0")
        assertEquals("map", URI(formatted.linkFor("10.0, 20.0")!!).host)
        assertEquals("map", URI(formatted.linkFor("30.0, 40.0")!!).host)
    }

    @Test
    @OriginalCase("MessageTextTests::A three-number decimal list is treated as a list, not a coordinate()")
    fun `a three-number list is not a coordinate`() = assertNull(fmt("values 1.0, 2.0, 3.0 here").linkFor("1.0, 2.0"))

    @Test
    @OriginalCase("MessageTextTests::A version-like string is not linkified()")
    fun `a version-like string is not linkified`() = assertNull(fmt("v1.2, 3.4 release").linkFor("1.2, 3.4"))

    @Test
    @OriginalCase("MessageTextTests::A coordinate inside an existing link range keeps the original link()")
    fun `a coordinate inside a contact chip keeps the chip link`() {
        val formatted = fmt("Add ${token("Base 37.7749, -122.4194")}")
        assertTrue(formatted.linkFor("37.7749, -122.4194")!!.startsWith("meshcore://contact/"))
    }

    @Test
    @OriginalCase("MessageTextTests::A linkified coordinate round-trips through parseMapURL with dot-decimal values()")
    fun `a linkified coordinate round-trips through parseMapURL`() {
        val url = fmt("37.334900, -122.009020").linkFor("37.334900, -122.009020")!!
        val parsed = assertNotNull(MeshCoreLinkParser.parseMapURL(url))
        assertTrue(abs(parsed.latitude - 37.3349) < 0.000001)
        assertTrue(abs(parsed.longitude - -122.00902) < 0.000001)
        assertTrue(url.contains("lat=37.334900") && url.contains("lon=-122.009020"))
    }

    @Test
    @OriginalCase("MessageTextTests::A coordinate ending a sentence is linkified, with the period left as plain text()")
    fun `a coordinate ending a sentence is linkified`() =
        assertEquals("map", java.net.URI(fmt("Meet at 37.7749, -122.4194.").linkFor("37.7749, -122.4194")!!).host)

    @Test
    @OriginalCase("MessageTextTests::A coordinate followed by other trailing punctuation is linkified()")
    fun `a coordinate followed by trailing punctuation is linkified`() =
        assertEquals("map", java.net.URI(fmt("Here: 37.7749, -122.4194!").linkFor("37.7749, -122.4194")!!).host)

    // MessageTextContactShareTests

    @Test
    @OriginalCase("MessageTextContactShareTests::Valid contact share token renders the contact name as a tappable add-contact link()")
    fun `valid token renders the name as an add-contact link`() {
        val token = token(contactName)
        val formatted = fmt("Add $token now")
        assertTrue(formatted.text.contains(contactName))
        assertFalse(formatted.text.contains(token), "raw share token should be replaced")
        assertEquals(contactName, formatted.linkedText(contactUrl(contactName)))
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Out-of-range contact type leaves the literal token untouched with no contact link()")
    fun `out-of-range type leaves the literal token`() {
        val literal = "<${"AB".repeat(32)}:300:x>"
        val formatted = fmt(literal)
        assertTrue(formatted.text.contains(literal))
        assertFalse(formatted.hasContactLink())
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Stray opening bracket is a no-op and does not crash()")
    fun `stray opening bracket is a no-op`() {
        val formatted = fmt("a < b")
        assertEquals("a < b", formatted.text)
        assertFalse(formatted.hasContactLink())
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Token adjacent to a URL styles both the contact link and the URL link()")
    fun `token adjacent to a URL styles both links`() {
        val formatted = fmt("${token(contactName)} https://example.com")
        assertTrue(formatted.tokens.any { it.url == contactUrl(contactName) })
        assertTrue(formatted.tokens.any { it.url == "https://example.com" })
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Hashtag after a token is still linked, proving the replacing pass runs before the snapshot()")
    fun `hashtag after a token is still linked`() {
        val formatted = fmt("${token(contactName)} #general")
        assertTrue(formatted.tokens.any { it.url == contactUrl(contactName) })
        assertTrue(formatted.tokens.any { it.url == "meshcoreone://hashtag/general" })
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Bidi control in the name is stripped from both the chip and the add-contact link URL()")
    fun `bidi control is stripped from chip and URL`() {
        val raw = "‮evil"
        val formatted = fmt("Add ${token(raw)}")
        assertEquals("evil", formatted.linkedText(contactUrl("evil")))
        assertNoBidiControls(formatted.text)
        assertFalse(formatted.tokens.any { it.url == contactUrl(raw) }, "link must not carry the raw bidi-override name")
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::Zero-width, format, and control scalars are stripped from the chip name and link URL()")
    fun `zero-width format and control scalars are stripped`() {
        val raw = "B​a‌s‍e\u000A\u0009"
        val formatted = fmt("Add ${token(raw)}")
        assertEquals("Base", formatted.linkedText(contactUrl("Base")))
        assertNoInvisibleScalars(formatted.text)
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::A contact token whose name sanitizes to empty leaves the literal token untouched()")
    fun `a name that sanitizes to empty leaves the literal token`() {
        val token = token("‮")
        val formatted = fmt(token)
        assertEquals(token, formatted.text)
        assertFalse(formatted.hasContactLink())
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::A mention pattern inside a token name is not rewritten by the mention pass()")
    fun `a mention pattern inside a token name is not rewritten`() {
        val name = "Ops @[Bob] base"
        assertEquals(name, fmt(token(name)).linkedText(contactUrl(name)))
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::A hashtag inside a token name keeps the chip link instead of becoming a hashtag link()")
    fun `a hashtag inside a token name stays in the chip`() {
        assertFalse(fmt(token("Base #general")).tokens.any { it.url == "meshcoreone://hashtag/general" })
    }

    @Test
    @OriginalCase("MessageTextContactShareTests::A meshcore URL inside a token name is not re-linked by the meshcore pass()")
    fun `a meshcore URL inside a token name is not re-linked`() {
        val formatted = fmt(token("join meshcore://channel/add?name=x&secret=00112233445566778899AABBCCDDEEFF"))
        assertFalse(formatted.tokens.any { it.url?.let { url -> URI(url).host } == "channel" })
    }

    // ChatCoordinateDetectorTests

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::A valid decimal pair is detected()")
    fun `a valid decimal pair is detected`() {
        val coordinate = assertNotNull(ChatCoordinateDetector.firstCoordinate("Meet at 37.334900, -122.009020 tonight"))
        assertTrue(abs(coordinate.latitude - 37.3349) < 0.000001)
        assertTrue(abs(coordinate.longitude - -122.00902) < 0.000001)
    }

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::An integer pair is not detected()")
    fun `an integer pair is not detected`() = assertNull(ChatCoordinateDetector.firstCoordinate("ratio is 3, 4 today"))

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::An out-of-range pair is rejected by the clamp, not the regex()")
    fun `an out-of-range pair is rejected by the clamp`() = assertNull(ChatCoordinateDetector.firstCoordinate("bad 200.0, 400.0 coord"))

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::A three-number decimal list is treated as a list, not a coordinate()")
    fun `a three-number decimal list is a list`() = assertNull(ChatCoordinateDetector.firstCoordinate("values 1.0, 2.0, 3.0 here"))

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::A version-like string is not detected()")
    fun `a version-like string is not detected`() = assertNull(ChatCoordinateDetector.firstCoordinate("v1.2, 3.4 release"))

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::firstCoordinate returns nil when there is no coordinate()")
    fun `firstCoordinate returns null without a coordinate`() = assertNull(ChatCoordinateDetector.firstCoordinate("no coordinates here"))

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::firstCoordinate returns the first of several coordinates()")
    fun `firstCoordinate returns the first of several`() {
        val coordinate = ChatCoordinateDetector.firstCoordinate("A 10.0, 20.0 and B 30.0, 40.0")!!
        assertEquals(10.0, coordinate.latitude)
        assertEquals(20.0, coordinate.longitude)
    }

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::matches returns every valid coordinate in document order()")
    fun `matches returns every coordinate in document order`() {
        val matches = ChatCoordinateDetector.matches("A 10.0, 20.0 and B 30.0, 40.0")
        assertEquals(2, matches.size)
        assertEquals(10.0, matches.first().coordinate.latitude)
        assertEquals(30.0, matches.last().coordinate.latitude)
    }

    @Test
    @OriginalCase("ChatCoordinateDetectorTests::A coordinate ending a sentence is detected, period excluded()")
    fun `a coordinate ending a sentence is detected`() {
        val text = "Meet at 37.7749, -122.4194."
        val match = ChatCoordinateDetector.matches(text).single()
        assertEquals(37.7749, match.coordinate.latitude)
        assertEquals("37.7749, -122.4194", text.substring(match.start, match.end))
    }

    // Independent Unicode 17 UCD properties; provenance and notice: docs/android/parity/README.md.
    private fun assertNoBidiControls(text: String) {
        val bidi = Regex("[\\x{061C}\\x{200E}-\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]")
        for (scalar in listOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069)) {
            assertTrue(bidi.containsMatchIn(String(Character.toChars(scalar))))
        }
        assertFalse(bidi.containsMatchIn(" \u00A0\u03A9\uD83D\uDE42"))
        assertFalse(bidi.containsMatchIn(text), "Visible output must contain no Bidi_Control scalars")
    }

    private fun assertNoInvisibleScalars(text: String) {
        val invisible = Regex(
            "[\\x{00AD}\\x{034F}\\x{061C}\\x{115F}-\\x{1160}\\x{17B4}-\\x{17B5}" +
                "\\x{180B}-\\x{180D}\\x{180E}\\x{180F}\\x{200B}-\\x{200F}\\x{202A}-\\x{202E}" +
                "\\x{2060}-\\x{2064}\\x{2065}\\x{2066}-\\x{206F}\\x{3164}\\x{FE00}-\\x{FE0F}\\x{FEFF}" +
                "\\x{FFA0}\\x{FFF0}-\\x{FFF8}\\x{1BCA0}-\\x{1BCA3}\\x{1D173}-\\x{1D17A}" +
                "\\x{E0000}\\x{E0001}\\x{E0002}-\\x{E001F}\\x{E0020}-\\x{E007F}" +
                "\\x{E0080}-\\x{E00FF}\\x{E0100}-\\x{E01EF}\\x{E01F0}-\\x{E0FFF}" +
                "\\x{0000}-\\x{001F}\\x{007F}-\\x{009F}" +
                "\\x{0600}-\\x{0605}\\x{06DD}\\x{070F}\\x{0890}-\\x{0891}\\x{08E2}" +
                "\\x{FFF9}-\\x{FFFB}\\x{110BD}\\x{110CD}\\x{13430}-\\x{1343F}]",
        )
        for (scalar in listOf(0x034F, 0x115F, 0x2065, 0x0600, 0x007F, 0xE0001, 0xE01EF, 0x13430)) {
            assertTrue(invisible.containsMatchIn(String(Character.toChars(scalar))))
        }
        assertFalse(invisible.containsMatchIn(" \u00A0\u03A9\uD83D\uDE42"))
        assertFalse(invisible.containsMatchIn(text), "Visible output must contain no Default_Ignorable_Code_Point, Cc or Cf scalars")
    }

    // Native: swiftc oracle probes (wp308_oracle.swift "coord")

    @Test
    fun `coordinate regex edge cases match the Swift oracle`() {
        fun found(text: String) = ChatCoordinateDetector.matches(text).map { text.substring(it.start, it.end) }
        assertEquals(listOf("1.5,2.5"), found("a 1.5,2.5."))
        assertEquals(emptyList(), found("1.5,2.5.1"))
        assertEquals(emptyList(), found("1.5, 2.5a"))
        assertEquals(listOf("-0.5 , 12.25"), found("-0.5 , 12.25!"))
        assertEquals(emptyList(), found("1234.5, 1.5"))
        assertEquals(listOf("1.5 , 2.5"), found("1.5 , 2.5"))
        assertEquals(emptyList(), found("x1.5, 2.5"))
        assertEquals(listOf("1.5,\n2.5"), found("1.5,\n2.5"))
        assertEquals(emptyList(), found("5.5, 6.5 ,7")) // third number after the pair: decimal-list guard
        // Fullwidth digits match the regex (ICU \d) but Double() rejects them, so no coordinate results.
        assertEquals(emptyList(), found("３７.５, 1.5"))
    }
}
