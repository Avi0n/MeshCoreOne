package one.meshcore.shared.chat.text

import one.meshcore.shared.contact.ContactCardUri
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-link-kind accessibility labels, dedup, cap, and preview-first ordering (iOS `MessageLinkAccessibility`). */
class MessageLinkAccessibilityTest {
    @Test fun `labels each link kind by its target`() {
        val contactUrl = ContactCardUri.export("Bob", ByteArray(KEY_BYTES) { 0xAB.toByte() }, ContactType.CHAT)
        val channelUrl = "meshcore://channel/add?name=Cool&secret=${"0".repeat(SECRET_HEX)}"
        val spans = listOf(
            span("https://example.com/x"),
            span("meshcore://map?lat=1.000000&lon=2.000000"),
            span(contactUrl),
            span(channelUrl),
            span("meshcoreone://mention/Bob"),
            span("meshcoreone://hashtag/weather"),
        )
        val labels = MessageLinkAccessibility.actions(previewUrl = null, spans = spans).map { it.label }
        assertEquals(
            listOf(
                MessageLinkAccessibility.Label.OpenWebLink("example.com"),
                MessageLinkAccessibility.Label.OpenMapLink,
                MessageLinkAccessibility.Label.AddContact("Bob"),
                MessageLinkAccessibility.Label.OpenChannel("Cool"),
                MessageLinkAccessibility.Label.OpenMention("Bob"),
                MessageLinkAccessibility.Label.OpenHashtag("weather"),
            ),
            labels,
        )
    }

    @Test fun `deduplicates by url`() {
        val spans = listOf(span("https://a.com"), span("https://a.com"))
        assertEquals(1, MessageLinkAccessibility.actions(previewUrl = null, spans = spans).size)
    }

    @Test fun `caps the action count`() {
        val spans = (0 until 12).map { span("https://a$it.com") }
        assertEquals(MessageLinkAccessibility.MAX_ACTIONS, MessageLinkAccessibility.actions(null, spans).size)
    }

    @Test fun `puts the preview url first`() {
        val actions = MessageLinkAccessibility.actions(
            previewUrl = "https://preview.com",
            spans = listOf(span("https://body.com")),
        )
        assertEquals("https://preview.com", actions.first().url)
    }

    private fun span(url: String) = TextSpan(start = 0, endExclusive = 1, kind = LinkKind.URL, url = url)

    private companion object {
        const val KEY_BYTES = 32
        const val SECRET_HEX = 32
    }
}
