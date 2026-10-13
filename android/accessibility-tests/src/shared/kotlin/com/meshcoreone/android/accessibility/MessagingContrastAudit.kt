// AndroidOnly: WP-503 Share the unchanged actual feature-span/effective-frame audit between JVM and Android runners.
package com.meshcoreone.android.accessibility

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.composer.LinkColor
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun auditMessagingContrast(
    compose: ComposeTestRule,
    content: (@Composable () -> Unit) -> Unit,
    surfaceContent: @Composable (@Composable () -> Unit) -> Unit,
    textLayout: (SemanticsNodeInteraction) -> TextLayoutResult,
    assertFont200Unclipped: (SemanticsNodeInteraction) -> Unit,
    paintedBackground: (SemanticsNodeInteraction, Color) -> ThemeColor,
) {
    val selected = mutableStateOf(ThemeRegistry.default)
    val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
    val high = mutableStateOf(false)
    val outgoing = mutableStateOf(true)
    val text = "@[Alice] see https://example.com #ops"
    val message = mutableStateOf(MessageDTO(
        id = UUID(0, 7), radioId = RadioId(UUID(0, 1)), text = text, timestamp = 100u,
        createdAt = Instant.ofEpochSecond(100), status = MessageStatus.DELIVERED,
    ))
    var surface = Color.Unspecified
    content {
        MeshCoreTheme(selected.value, preference.value, highContrast = high.value, motionScale = 0f) {
            val color = if (outgoing.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            SideEffect { surface = color }
            surfaceContent {
                ChatTimeline(
                    ChatTimelineState(messages = listOf(message.value),
                        rows = listOf(TimelineRow.Message(message.value, true, true)),
                        hasLoadedOnce = true, hasMoreMessages = false, initialAnchorConsumed = true),
                    ChatTimelineCallbacks({}, {}, {}, { _, _ -> }, {}, {}),
                    messageBody = { value, sent -> MessageBody(value.text, sent, "Alice", {}) },
                )
            }
        }
    }
    val failures = mutableListOf<String>()
    var frames = 0
    var spans = 0
    for (theme in ThemeRegistry.allThemes) {
        for (scheme in ColorScheme.entries.filter { theme.preferredColorScheme == null || theme.preferredColorScheme == it }) {
            for (contrast in listOf(false, true)) {
                frames++
                for (sent in listOf(false, true)) {
                    compose.runOnIdle {
                        selected.value = theme
                        preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                        high.value = contrast
                        outgoing.value = sent
                        message.value = message.value.copy(
                            direction = if (sent) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                            channelIndex = if (sent) null else 0u,
                            senderNodeName = if (sent) null else "Alice",
                        )
                    }
                    val formatted = MessageTextFormatter.format(text, sent, "Alice")
                    val node = compose.onNode(
                        hasText(formatted.text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                        useUnmergedTree = true,
                    )
                    val layout = textLayout(node)
                    assertFont200Unclipped(node)
                    val background = paintedBackground(node, surface)
                    val annotated = layout.layoutInput.text
                    val emitted = annotated.spanStyles + annotated.getLinkAnnotations(0, annotated.length).mapNotNull { link ->
                        link.item.styles?.style?.let { androidx.compose.ui.text.AnnotatedString.Range(it, link.start, link.end) }
                    }
                    assertTrue("No emitted span colors were measured", emitted.isNotEmpty())
                    val boundaries = (listOf(0, annotated.length) + emitted.flatMap { listOf(it.start, it.end) }).distinct().sorted()
                    for ((start, end) in boundaries.zipWithNext()) {
                        val style = emitted.filter { it.start <= start && it.end >= end }
                            .fold(androidx.compose.ui.text.SpanStyle()) { combined, span -> combined.merge(span.item) }
                        assertTrue("Unresolved emitted foreground at $start-$end", style.color.isSpecified)
                        val fill = if (style.background.isSpecified) style.background.toThemeColor().compositeOver(background) else background
                        val ratio = WCAGContrast.contrastRatio(style.color.toThemeColor(), fill)
                        val identityToken = formatted.tokens.any {
                            it.start <= start && it.end >= end && it.color == LinkColor.MENTION_IDENTITY
                        }
                        val floor = if (contrast && identityToken) 7.0 else 4.5
                        val identity = "${theme.id.rawValue}:$scheme:hc=$contrast:outgoing=$sent:$start-$end"
                        println("WP503_CONTRAST|$identity|ratio=$ratio|minimum=$floor")
                        if (ratio < floor) failures += "$identity=$ratio<$floor"
                        spans++
                    }
                }
            }
        }
    }
    assertEquals("Do not silently narrow the effective frame family", 38, frames)
    assertTrue("Zero span measurements is not acceptance", spans >= 76)
    assertTrue("Actual painted-feature contrast below 4.5:1: ${failures.joinToString()}", failures.isEmpty())
}
