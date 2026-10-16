package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragmentLayoutTest {
    @Test
    fun textAndInlineImageStayInTheBox() {
        val layout = fragmentLayout(
            listOf(text("hello"), inlineImage("https://example.com/a.png")),
        )
        assertEquals("hello", layout.textPayload?.raw)
        assertTrue(layout.inlineImage != null)
        assertTrue(layout.siblings.isEmpty())
    }

    @Test
    fun reactionLinkMapAndMalwareAreSiblings() {
        val layout = fragmentLayout(
            listOf(
                text(),
                MessageFragment.ReactionSummary("👍:1"),
                inlineImage(),
                MessageFragment.LinkPreview(LinkPreviewFragmentState(LinkPreviewFragmentState.Mode.Idle)),
                MessageFragment.MapPreview(
                    MapPreviewFragmentState(1.0, 2.0, isDark = false, isOffline = false, isReady = true),
                ),
                MessageFragment.MalwareWarning("https://bad.example"),
            ),
        )
        assertEquals("hi", layout.textPayload?.raw)
        assertTrue(layout.inlineImage != null)
        assertEquals(
            listOf(
                MessageFragment.ReactionSummary::class,
                MessageFragment.LinkPreview::class,
                MessageFragment.MapPreview::class,
                MessageFragment.MalwareWarning::class,
            ),
            layout.siblings.map { it::class },
        )
    }

    @Test
    fun firstTextAndFirstImageWin() {
        val layout = fragmentLayout(
            listOf(text("first"), text("second"), inlineImage("one"), inlineImage("two")),
        )
        assertEquals("first", layout.textPayload?.raw)
        assertEquals(InlineImage.LoadState.Disabled("one"), layout.inlineImage?.state)
        assertTrue(layout.siblings.isEmpty())
    }

    @Test
    fun emptyContentYieldsAnEmptyLayout() {
        val layout = fragmentLayout(emptyList())
        assertNull(layout.textPayload)
        assertNull(layout.inlineImage)
        assertTrue(layout.siblings.isEmpty())
    }

    private fun text(raw: String = "hi"): MessageFragment.Text = MessageFragment.Text(
        MessageTextPayload(
            raw = raw,
            formattedText = null,
            baseColor = BaseColorSlot.INCOMING,
            isOutgoing = false,
            currentUserName = "Me",
        ),
    )

    private fun inlineImage(url: String = "https://example.com/a.png"): MessageFragment.InlineImage =
        MessageFragment.InlineImage(InlineImage(InlineImage.LoadState.Disabled(url), autoPlayGIFs = false))
}
