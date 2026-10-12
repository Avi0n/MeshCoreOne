// AndroidOnly: WP-308 Regression coverage for rich spans on native Material bubbles and composited self-mention fills.
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MaterialRoles
import com.meshcoreone.android.core.designsystem.ThemeFrame
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.feature.chats.composer.LinkColor
import com.meshcoreone.android.feature.chats.composer.LinkKind
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MessageBodyContrastTest {
    private fun frames(): List<ThemeFrame> = ThemeRegistry.allThemes.flatMap { theme ->
        ColorScheme.entries.filter { theme.preferredColorScheme == null || theme.preferredColorScheme == it }.flatMap { scheme ->
            listOf(false, true).map { highContrast -> theme.resolve(scheme, highContrast) }
        }
    }

    private fun AnnotatedString.styleAt(start: Int, end: Int): SpanStyle {
        val emitted = spanStyles + getLinkAnnotations(0, length).map { link ->
            AnnotatedString.Range(requireNotNull(link.item.styles?.style), link.start, link.end)
        }
        return emitted.filter { it.start <= start && it.end >= end }
            .fold(SpanStyle()) { combined, span -> combined.merge(span.item) }
    }

    @Test
    fun `rich ranges clear native bubbles and self mention fills in all 38 frames`() {
        val frames = frames()
        assertEquals(38, frames.size)
        val names = listOf("Alice", "Bob", "Field Base", "\u4e2d\u6587", "\u05e9\u05dc\u05d5\u05dd", "e\u0301", "\uD83D\uDC69\u200D\uD83D\uDE80")
        var segments = 0
        for (frame in frames) for (outgoing in listOf(false, true)) for (name in names) for (self in listOf(false, true)) {
            val roles = MaterialRoles.from(frame)
            val bubble = if (outgoing) roles.primaryContainer else roles.surfaceVariant
            val base = if (outgoing) roles.onPrimaryContainer else roles.onSurfaceVariant
            val formatted = MessageTextFormatter.format("@[$name] see https://example.com #ops", outgoing, name.takeIf { self })
            val annotated = formatted.toAnnotatedString(base.toComposeColor(), frame, outgoing, bubble.toComposeColor()) {}
            assertEquals(formatted.text, annotated.text)
            val boundaries = (listOf(0, annotated.length) + formatted.tokens.flatMap { listOf(it.start, it.end) }).distinct().sorted()
            for ((start, end) in boundaries.zipWithNext()) {
                val style = annotated.styleAt(start, end)
                assertTrue(style.color.isSpecified)
                val background = if (style.background.isSpecified) style.background.toThemeColor().compositeOver(bubble) else bubble
                val identity = formatted.tokens.any { it.start <= start && it.end >= end && it.color == LinkColor.MENTION_IDENTITY }
                val floor = if (identity && frame.highContrast) 7.0 else 4.5
                val ratio = WCAGContrast.contrastRatio(style.color.toThemeColor(), background)
                assertTrue(ratio >= floor, "${frame.theme.id}: ${frame.colorScheme}, hc=${frame.highContrast}, outgoing=$outgoing, self=$self, $name: $start-$end = $ratio < $floor")
                segments++
            }
            val mention = annotated.styleAt(0, name.length + 1)
            assertEquals(self, mention.background.isSpecified)
            if (self) assertEquals(if (outgoing) 0.3f else 0.15f, mention.background.alpha)
        }
        assertEquals(38 * 2 * names.size * 2 * 5, segments)
    }

    @Test
    fun `source outgoing text override no longer uses an accent color pairing on the pale native bubble`() {
        for ((id, scheme) in listOf(ThemeId.DEFAULT to ColorScheme.LIGHT, ThemeId.FERN to ColorScheme.DARK)) {
            val frame = requireNotNull(ThemeRegistry.theme(id)).resolve(scheme, false)
            val roles = MaterialRoles.from(frame)
            assertTrue(WCAGContrast.contrastRatio(frame.outgoingText, roles.primaryContainer) < 4.5)
            val formatted = MessageTextFormatter.format("hello #ops", true)
            val annotated = formatted.toAnnotatedString(roles.onPrimaryContainer.toComposeColor(), frame, true, roles.primaryContainer.toComposeColor()) {}
            val hashtag = formatted.tokens.single()
            assertEquals(LinkColor.OUTGOING_TEXT, hashtag.color)
            assertTrue(WCAGContrast.contrastRatio(annotated.styleAt(hashtag.start, hashtag.end).color.toThemeColor(), roles.primaryContainer) >= 4.5)
        }
    }

    @Test
    fun `styling keeps token ranges links actions underline and bold for every link kind`() {
        val text = "@[Alice] https://example.com #ops meshcore://channel/add?name=ops 37.334900, -122.009020 <${"AB".repeat(32)}:1:Base>"
        val frame = frames().first()
        val bubble = MaterialRoles.from(frame).surfaceVariant.toComposeColor()
        val formatted = MessageTextFormatter.format(text, false, "Alice")
        val opened = mutableListOf<String>()
        val annotated = formatted.toAnnotatedString(Color.Black, frame, false, bubble, opened::add)
        assertEquals(LinkKind.entries.toSet(), formatted.tokens.map { it.kind }.toSet())
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(formatted.tokens.size, links.size)
        for ((token, link) in formatted.tokens.zip(links)) {
            assertEquals(token.start, link.start)
            assertEquals(token.end, link.end)
            val clickable = link.item as LinkAnnotation.Clickable
            assertEquals(token.url, clickable.tag)
            val style = requireNotNull(clickable.styles?.style)
            assertEquals(if (token.underline) TextDecoration.Underline else null, style.textDecoration)
            assertEquals(if (token.bold) FontWeight.Bold else null, style.fontWeight)
            requireNotNull(clickable.linkInteractionListener).onClick(clickable)
        }
        assertEquals(formatted.links, opened)
    }

    @Test
    fun `identity colors use the bubble luminance without changing avatar identity roles`() {
        for (frame in frames()) {
            val bubble = MaterialRoles.from(frame).surfaceVariant
            val formatted = MessageTextFormatter.format("@[Alice]")
            val annotated = formatted.toAnnotatedString(Color.Black, frame, false, bubble.toComposeColor()) {}
            val preferred = frame.theme.identityGamut.color("Alice", listOf(bubble.luminance), frame.highContrast)
            val expected = accessibleForeground(preferred, bubble, WCAGContrast.floor(frame.highContrast)).toComposeColor()
            assertEquals(expected, annotated.styleAt(0, annotated.length).color)
            assertFalse(annotated.styleAt(0, annotated.length).background.isSpecified)
        }
    }

    @Test
    fun `explicit background and base color participate in contextual styling`() {
        val frame = frames().first()
        val formatted = MessageTextFormatter.format("plain https://example.com")
        for ((bubble, base) in listOf(Color.Black to Color.White, Color.White to Color.Black)) {
            val annotated = formatted.toAnnotatedString(base, frame, false, bubble) {}
            assertEquals(base, annotated.styleAt(0, 6).color)
            assertEquals(base, annotated.styleAt(6, annotated.length).color)
        }
        val empty = MessageTextFormatter.format("").toAnnotatedString(Color.White, frame, true, Color.Black) {}
        assertEquals("", empty.text)
        assertTrue(empty.getLinkAnnotations(0, empty.length).isEmpty())
    }
}
