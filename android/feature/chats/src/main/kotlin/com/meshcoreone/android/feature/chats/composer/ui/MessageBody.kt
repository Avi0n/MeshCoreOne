// PortedFrom: MC1/Views/Chats/Components/MessageText.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkStyler.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.ThemeFrame
import com.meshcoreone.android.core.designsystem.ThemeColor
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.feature.chats.composer.FormattedMessage
import com.meshcoreone.android.feature.chats.composer.LinkColor
import com.meshcoreone.android.feature.chats.composer.LinkToken
import com.meshcoreone.android.feature.chats.composer.MessageLinkAccessibility
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter

private const val INCOMING_SELF_MENTION_ALPHA = 0.15f
private const val OUTGOING_SELF_MENTION_ALPHA = 0.3f

private fun tokenColor(token: LinkToken, base: ThemeColor, frame: ThemeFrame, background: ThemeColor): ThemeColor = when (token.color) {
    LinkColor.BASE -> base
    LinkColor.MENTION_IDENTITY -> token.colorKey?.let {
        frame.theme.identityGamut.color(it, listOf(background.luminance), frame.highContrast)
    } ?: base
    LinkColor.HASHTAG -> frame.hashtag
    LinkColor.OUTGOING_TEXT -> frame.outgoingText
}

/** One `AnnotatedString` from the token model: base color, then per-token color, underline, bold, self-mention fill. */
internal fun FormattedMessage.toAnnotatedString(
    base: Color,
    frame: ThemeFrame,
    isOutgoing: Boolean,
    background: Color,
    onLink: (String) -> Unit,
): AnnotatedString {
    val bubble = background.toThemeColor()
    val readableBase = accessibleForeground(base.toThemeColor(), bubble, WCAGContrast.AA_FLOOR)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = readableBase.toComposeColor()), 0, text.length)
        for (token in tokens) {
            val floor = if (token.color == LinkColor.MENTION_IDENTITY) WCAGContrast.floor(frame.highContrast) else WCAGContrast.AA_FLOOR
            val preferred = accessibleForeground(tokenColor(token, readableBase, frame, bubble), bubble, floor)
            val fill = if (token.selfMention) {
                preferred.copy(alpha = (if (isOutgoing) OUTGOING_SELF_MENTION_ALPHA else INCOMING_SELF_MENTION_ALPHA).toDouble())
            } else {
                null
            }
            // Freeze the source-opacity highlight before correcting its foreground, avoiding a circular color/fill dependency.
            val color = accessibleForeground(preferred, fill?.compositeOver(bubble) ?: bubble, floor)
            val style = SpanStyle(
                color = color.toComposeColor(),
                fontWeight = if (token.bold) FontWeight.Bold else null,
                textDecoration = if (token.underline) TextDecoration.Underline else null,
                background = fill?.toComposeColor() ?: Color.Unspecified,
            )
            val url = token.url
            if (url == null) {
                addStyle(style, token.start, token.end)
            } else {
                addLink(
                    LinkAnnotation.Clickable(url, TextLinkStyles(style)) { onLink(url) },
                    token.start, token.end,
                )
            }
        }
}
    }

/**
 * Message text with tappable mentions, links, hashtags and coordinates. TalkBack gets one named
 * custom action per link (preview URL first, deduplicated, capped), derived from the same model.
 * [backgroundColor] is the opaque painted surface; callers with other bubble roles or translucent
 * fills must supply their actual composited background rather than the default timeline roles.
 */
@Composable
fun MessageBody(
    text: String,
    isOutgoing: Boolean,
    currentUserName: String?,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    previewUrl: String? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    baseColor: Color = LocalContentColor.current,
    backgroundColor: Color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
) {
    val frame = LocalMeshTheme.current.frame
    val names = rememberLinkActionNames()
    val formatted = remember(text, isOutgoing, currentUserName) { MessageTextFormatter.format(text, isOutgoing, currentUserName) }
    val annotated = remember(formatted, baseColor, frame, isOutgoing, backgroundColor, onLink) {
        formatted.toAnnotatedString(baseColor, frame, isOutgoing, backgroundColor, onLink)
    }
    val actions = remember(formatted, previewUrl, names) { MessageLinkAccessibility.actions(previewUrl, formatted, names) }
    BasicText(
        annotated,
        modifier.semantics {
            customActions = actions.map { action -> CustomAccessibilityAction(action.name) { onLink(action.url); true } }
        },
        style = style,
    )
}
