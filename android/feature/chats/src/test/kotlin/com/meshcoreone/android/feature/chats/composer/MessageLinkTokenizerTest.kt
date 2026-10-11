// PortedFrom: MC1Tests/Views/Chats/Linkify/MessageLinkTokenizerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/Linkify/MessageLinkAccessibilityTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

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

/** English strings copied from core:l10n `l10n_app_chats_chats_message_action_*` (Swift Localizable). */
internal object EnglishLinkActionNames : LinkActionNames {
    override fun openLink() = "Open Link"
    override fun openWebLink(host: String) = "Open Link: $host"
    override fun openMapLink() = "Open Map"
    override fun addContact(name: String) = "Add Contact: $name"
    override fun openChannel(name: String) = "Open Channel: $name"
    override fun openMention(name: String) = "Mention: $name"
    override fun openHashtag(name: String) = "Open #$name"
}

class MessageLinkTokenizerTest {
    private val context = MessageLinkTokenizer.StyleContext(isOutgoing = false)

    private fun tokenize(text: String, pre: List<LinkToken> = emptyList()) = MessageLinkTokenizer.tokenize(text, pre, context)
    private fun MessageLinkTokenizer.Result.covering(substring: String, text: String): LinkToken? {
        val start = text.indexOf(substring)
        val end = start + substring.length
        return tokens.firstOrNull { it.start < end && start < it.end }
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::URL detector emits a url token()")
    fun `URL detector emits a url token`() {
        val text = "see https://example.com here"
        val token = tokenize(text).covering("https://example.com", text)!!
        assertEquals(LinkKind.URL, token.kind)
        assertEquals("https", URI(token.url!!).scheme)
        assertTrue(token.underline)
        assertFalse(token.bold)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::Hashtag detector emits a bold, non-underlined token()")
    fun `hashtag detector emits a bold non-underlined token`() {
        val text = "join #general today"
        val token = tokenize(text).covering("#general", text)!!
        assertEquals(LinkKind.HASHTAG, token.kind)
        assertEquals("meshcoreone://hashtag/general", token.url)
        assertTrue(token.bold)
        assertFalse(token.underline)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::MeshCore link detector emits a meshcore token and trims trailing punctuation()")
    fun `meshcore detector trims trailing punctuation`() {
        val text = "open meshcore://contact/add?name=Bob."
        val token = tokenize(text).covering("meshcore://contact", text)!!
        assertEquals(LinkKind.MESHCORE_LINK, token.kind)
        assertEquals("contact", URI(token.url!!).host)
        assertTrue(text.substring(token.start, token.end).last() != '.')
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::MeshCore link with a non-contact, non-channel host is ignored()")
    fun `meshcore link with another host is ignored`() = assertTrue(tokenize("open meshcore://other/path").tokens.isEmpty())

    @Test
    @OriginalCase("MessageLinkTokenizerTests::Coordinate detector emits a coordinate token()")
    fun `coordinate detector emits a coordinate token`() {
        val text = "at 37.7749, -122.4194 now"
        val token = tokenize(text).covering("37.7749, -122.4194", text)!!
        assertEquals(LinkKind.COORDINATE, token.kind)
        assertEquals("map", URI(token.url!!).host)
        assertTrue(token.underline)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::A pre-pass mention span outranks a URL detection over the same characters()")
    fun `a pre-pass mention span outranks a URL over the same characters`() {
        val normalized = "Hey @www.WCMesh.com hi"
        val start = normalized.indexOf("@www.WCMesh.com")
        val span = LinkToken(start, start + "@www.WCMesh.com".length, LinkKind.MENTION, "meshcoreone://mention/www.WCMesh.com")
        val covering = tokenize(normalized, listOf(span)).tokens.filter { it.overlaps(span) }
        assertEquals(1, covering.size)
        assertEquals(LinkKind.MENTION, covering.single().kind)
        assertTrue(covering.single().url!!.startsWith("meshcoreone://"))
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::The merged token stream is sorted and non-overlapping()")
    fun `merged stream is sorted and non-overlapping`() {
        val tokens = tokenize("@x see https://a.com #ops at 37.7749, -122.4194 end").tokens
        assertTrue(tokens.size >= 3)
        for (index in 0 until tokens.size - 1) {
            assertTrue(tokens[index].start <= tokens[index + 1].start, "tokens must be sorted")
            assertFalse(tokens[index].overlaps(tokens[index + 1]), "tokens must not overlap")
        }
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::A hashtag inside a URL is excluded via the shared URL ranges()")
    fun `a hashtag inside a URL is excluded`() {
        val text = "Check https://example.com#general and #ops"
        val result = tokenize(text)
        assertEquals(LinkKind.URL, result.covering("#general", text)!!.kind)
        assertEquals(LinkKind.HASHTAG, result.covering("#ops", text)!!.kind)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::A hashtag inside a meshcore link wins the overlap and the meshcore link is dropped()")
    fun `a hashtag inside a meshcore channel link wins`() {
        val text = "meshcore://channel/add?name=ops#general"
        val result = tokenize(text)
        assertEquals(LinkKind.HASHTAG, result.covering("#general", text)!!.kind)
        assertTrue(result.tokens.none { it.kind == LinkKind.MESHCORE_LINK })
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::A hashtag inside a meshcore contact link also wins the overlap and drops the link()")
    fun `a hashtag inside a meshcore contact link wins`() {
        val text = "meshcore://contact/add?public_key=${"AB".repeat(32)}#ops"
        val result = tokenize(text)
        assertEquals(LinkKind.HASHTAG, result.covering("#ops", text)!!.kind)
        assertTrue(result.tokens.none { it.kind == LinkKind.MESHCORE_LINK })
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::mapCoordinate is the first surviving coordinate in document order()")
    fun `mapCoordinate is the first surviving coordinate`() {
        val coordinate = tokenize("first 37.7749, -122.4194 then 10.0, 20.0").mapCoordinate!!
        assertTrue(abs(coordinate.latitude - 37.7749) < 0.0001)
        assertTrue(abs(coordinate.longitude - -122.4194) < 0.0001)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::mapCoordinate skips a coordinate that lost to a higher-priority token()")
    fun `mapCoordinate skips a coordinate that lost to a higher-priority token`() {
        val normalized = "Base 1.0, 2.0 then 3.5, 4.5"
        val chip = LinkToken(0, "Base 1.0, 2.0".length, LinkKind.CONTACT_SHARE, "meshcore://contact/add?name=Base")
        val coordinate = tokenize(normalized, listOf(chip)).mapCoordinate!!
        assertTrue(abs(coordinate.latitude - 3.5) < 0.0001)
        assertTrue(abs(coordinate.longitude - 4.5) < 0.0001)
    }

    @Test
    @OriginalCase("MessageLinkTokenizerTests::No coordinate yields a nil mapCoordinate()")
    fun `no coordinate yields a null mapCoordinate`() = assertNull(tokenize("no coordinates here at all").mapCoordinate)

    @Test
    @OriginalCase("MessageLinkTokenizerTests::A coordinate token URL round-trips through MeshCoreURLParser with dot-decimals()")
    fun `a coordinate token URL round-trips through the link parser`() {
        val text = "37.334900, -122.009020"
        val url = tokenize(text).covering(text, text)!!.url!!
        val coordinate = assertNotNull(MeshCoreLinkParser.parseMapURL(url))
        assertTrue(abs(coordinate.latitude - 37.3349) < 0.000001)
        assertTrue(abs(coordinate.longitude - -122.00902) < 0.000001)
        assertTrue(url.contains("lat=37.334900") && url.contains("lon=-122.009020"))
    }

    // MessageLinkAccessibilityTests

    private fun linked(vararg segments: Pair<String, String?>): FormattedMessage {
        val text = StringBuilder()
        val tokens = ArrayList<LinkToken>()
        for ((piece, url) in segments) {
            if (url != null) tokens += LinkToken(text.length, text.length + piece.length, LinkKind.URL, url)
            text.append(piece)
        }
        return FormattedMessage(text.toString(), tokens, null)
    }

    private fun actions(preview: String?, formatted: FormattedMessage?) =
        MessageLinkAccessibility.actions(preview, formatted, EnglishLinkActionNames)

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::Each body-text link kind yields a distinctly-named action in document order()")
    fun `each link kind yields a distinctly named action in document order`() {
        val actions = actions(null, MessageTextFormatter.format("@[Bob] see https://example.com #ops at 37.7749, -122.4194"))
        assertEquals(4, actions.size)
        assertEquals("meshcoreone://mention/Bob", actions[0].url)
        assertEquals("Mention: Bob", actions[0].name)
        assertEquals("https", URI(actions[1].url).scheme)
        assertEquals("Open Link: example.com", actions[1].name)
        assertEquals("meshcoreone://hashtag/ops", actions[2].url)
        assertEquals("Open #ops", actions[2].name)
        assertEquals("map", URI(actions[3].url).host)
        assertEquals("Open Map", actions[3].name)
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::A shared contact link is named from its parsed contact name()")
    fun `a shared contact link is named from its parsed name`() {
        val uri = "meshcore://contact/add?name=Alice&public_key=${"AB".repeat(32)}&type=1"
        val actions = actions(null, linked("Alice" to uri))
        assertEquals(1, actions.size)
        assertEquals("Add Contact: Alice", actions[0].name)
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::A shared channel link is named from its parsed channel name()")
    fun `a shared channel link is named from its parsed name`() {
        val uri = "meshcore://channel/add?name=Ops&secret=${"CD".repeat(16)}"
        val actions = actions(null, linked("Ops" to uri))
        assertEquals(1, actions.size)
        assertEquals("Open Channel: Ops", actions[0].name)
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::The preview URL is surfaced before body-text links()")
    fun `the preview URL comes before body links`() {
        val actions = actions("https://preview.example", linked("go " to null, "body" to "https://body.example"))
        assertEquals(listOf("https://preview.example", "https://body.example"), actions.map { it.url })
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::Duplicate URLs collapse to a single action()")
    fun `duplicate URLs collapse`() {
        val shared = "https://dup.example"
        val actions = actions(shared, linked("a" to shared, " " to null, "b" to shared))
        assertEquals(listOf(shared), actions.map { it.url })
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::The action count is capped()")
    fun `the action count is capped`() {
        val segments = (0 until MessageLinkAccessibility.MAX_ACTIONS + 4).map { "link$it " to ("https://e$it.example" as String?) }
        assertEquals(MessageLinkAccessibility.MAX_ACTIONS, actions(null, linked(*segments.toTypedArray())).size)
    }

    @Test
    @OriginalCase("MessageLinkAccessibilityTests::No links yields no actions()")
    fun `no links yields no actions`() {
        assertTrue(actions(null, linked("plain" to null)).isEmpty())
        assertTrue(actions(null, null).isEmpty())
    }

    // Native

    @Test
    fun `unparseable meshcore and mention links fall back to the generic action`() {
        val actions = actions(null, linked(
            "a" to "meshcore://contact/add?name=x", "b" to "meshcoreone://mention/", "c" to "meshcoreone://hashtag/%ZZ",
            "d" to "meshcore://other", "e" to "ftp://x", "f" to "https:///nohost",
        ))
        assertEquals(List(6) { "Open Link" }, actions.map { it.name })
    }

    @Test
    fun `hashtag action decodes the percent-encoded channel`() {
        assertEquals("Open #a b", actions(null, linked("x" to "meshcoreone://hashtag/a%20b")).single().name)
    }
}
