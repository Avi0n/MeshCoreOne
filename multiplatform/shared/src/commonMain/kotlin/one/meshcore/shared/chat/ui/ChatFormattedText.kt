package one.meshcore.shared.chat.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import one.meshcore.shared.chat.text.FormattedText
import one.meshcore.shared.chat.text.LinkKind
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.text.TextSpan
import one.meshcore.shared.contact.ui.toComposeColor
import one.meshcore.shared.theme.LocalAppTheme
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.LocalHighContrast

/**
 * Renders a linkified [FormattedText] body as a tappable `AnnotatedString`. The framework-free span model
 * carries only structure (range, kind, target); the per-kind styling — colour, underline, bold, and the
 * self-mention background — is resolved here from the theme, the bubble's inherited content colour, and,
 * for an incoming mention, the deterministic identity colour, mirroring how the iOS `MessageLinkStyler`
 * froze those colours at build time.
 *
 * A tap on a web link opens the browser directly (a universal action needing no app state); every in-app
 * target — `meshcore://` links, mentions, hashtags, coordinates — dispatches to [onLinkTap], which the
 * caller wires to the deep-link router and the mention/hashtag resolution seams.
 *
 * Incoming mentions use the selected theme's [one.meshcore.shared.avatar.IdentityGamut] against
 * that theme's surfaces, the same source contact avatars use. Incoming hashtags use the theme's
 * Hashtag token, not Material primary. Both honor [LocalUseDarkTheme] and [LocalHighContrast]
 * so Appearance Light on a dark phone does not pick the system-dark palette.
 */
@Composable
fun ChatFormattedText(
    formatted: FormattedText,
    outgoing: Boolean,
    style: TextStyle,
    onLinkTap: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAppTheme.current
    val dark = LocalUseDarkTheme.current
    val highContrast = LocalHighContrast.current
    val baseColor = LocalContentColor.current
    val uriHandler = LocalUriHandler.current
    val palette = ChatSpanPalette(
        outgoing = outgoing,
        theme = theme,
        useDark = dark,
        highContrast = highContrast,
        baseColor = baseColor,
    )
    val annotated = buildAnnotatedString {
        append(formatted.displayString)
        for (span in formatted.spans) {
            if (span.start >= span.endExclusive || span.endExclusive > formatted.displayString.length) continue
            addStyle(
                styledChatSpan(span, palette),
                span.start,
                span.endExclusive,
            )
            val url = span.url
            val target = url?.let { LinkTarget.parse(it) }
            if (url != null && target != null) {
                addLink(
                    LinkAnnotation.Clickable(tag = url, linkInteractionListener = {
                        when (target) {
                            is LinkTarget.Web -> uriHandler.openUri(target.url)
                            else -> onLinkTap(target)
                        }
                    }),
                    span.start,
                    span.endExclusive,
                )
            }
        }
    }
    Text(text = annotated, style = style, color = baseColor, modifier = modifier)
}

/**
 * The frozen styling for one span. Links, coordinates, and contact chips underline in the base text
 * colour; a hashtag is bold in the hashtag colour (the base colour when outgoing); a mention underlines in
 * its identity colour (the base colour when outgoing) with a translucent self-mention background.
 */
internal fun styledChatSpan(span: TextSpan, palette: ChatSpanPalette): SpanStyle = when (span.kind) {
    LinkKind.URL, LinkKind.MESHCORE_LINK, LinkKind.COORDINATE, LinkKind.CONTACT_SHARE ->
        SpanStyle(color = palette.baseColor, textDecoration = TextDecoration.Underline)

    LinkKind.HASHTAG -> {
        val hashtagColor = Color(palette.theme.hashtagArgb(palette.useDark, palette.highContrast))
        SpanStyle(
            color = if (palette.outgoing) palette.baseColor else hashtagColor,
            fontWeight = FontWeight.Bold,
        )
    }

    LinkKind.MENTION -> {
        val foreground = if (palette.outgoing || span.mentionName == null) {
            palette.baseColor
        } else {
            palette.theme.identityGamut.colorForName(
                name = span.mentionName,
                backgroundLuminances = palette.theme.identitySurfaceLuminances(palette.useDark),
                highContrast = palette.highContrast,
            ).toComposeColor()
        }
        val background = if (span.isSelfMention) {
            foreground.copy(
                alpha = if (palette.outgoing) SELF_MENTION_ALPHA_OUTGOING else SELF_MENTION_ALPHA_INCOMING,
            )
        } else {
            Color.Unspecified
        }
        SpanStyle(color = foreground, background = background, textDecoration = TextDecoration.Underline)
    }
}

// The self-mention highlight alpha behind a mention of the local user (iOS §2): heavier on the filled
// outgoing bubble, lighter behind the incoming identity colour.
private const val SELF_MENTION_ALPHA_OUTGOING = 0.3f
private const val SELF_MENTION_ALPHA_INCOMING = 0.15f

/** The linkified body of a text fragment, or its plain raw text when no formatted form is available. */
@Composable
fun MessageBodyText(
    formatted: FormattedText?,
    raw: String,
    outgoing: Boolean,
    style: TextStyle,
    onLinkTap: (LinkTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (formatted == null) {
        Text(text = AnnotatedString(raw), style = style, modifier = modifier)
    } else {
        ChatFormattedText(
            formatted = formatted,
            outgoing = outgoing,
            style = style,
            onLinkTap = onLinkTap,
            modifier = modifier,
        )
    }
}
