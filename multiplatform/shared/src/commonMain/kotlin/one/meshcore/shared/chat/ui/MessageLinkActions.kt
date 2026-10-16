package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.text.FormattedText
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.text.MessageLinkAccessibility
import one.meshcore.shared.chat.text.TextSpan
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_add_contact
import one.meshcore.shared.resources.chats_message_action_open_channel
import one.meshcore.shared.resources.chats_message_action_open_hashtag
import one.meshcore.shared.resources.chats_message_action_open_link
import one.meshcore.shared.resources.chats_message_action_open_map_link
import one.meshcore.shared.resources.chats_message_action_open_mention
import one.meshcore.shared.resources.chats_message_action_open_web_link
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One activation action per link in the row — the preview card's destination first, then each linked span
 * in reading order, capped by [MessageLinkAccessibility.MAX_ACTIONS]. Each label names its own target, so
 * two links in one message are told apart before either is activated.
 */
@Composable
internal fun messageLinkActions(item: MessageItem, onLinkTap: (LinkTarget) -> Unit): List<CustomAccessibilityAction> =
    linkActions(messageLinkActionTargets(item), onLinkTap)

/**
 * TalkBack open-actions for a linkified body that is not a chat [MessageItem] (room bubbles). Same
 * labels and URL parse as the chat row; no preview-card URL because rooms have none. Mirrors iOS
 * `RoomMessageBubble.accessibilityLinkActions`.
 */
@Composable
internal fun formattedTextLinkActions(
    formatted: FormattedText?,
    onLinkTap: (LinkTarget) -> Unit,
): List<CustomAccessibilityAction> = linkActions(formattedTextLinkActionTargets(formatted), onLinkTap)

/**
 * The label-and-target pairs the row's link actions are built from: the preview card's destination first,
 * then each linked span in reading order. A URL no chat surface claims contributes no action.
 */
internal fun messageLinkActionTargets(item: MessageItem): List<Pair<MessageLinkAccessibility.Label, LinkTarget>> {
    val previewURL = item.content.filterIsInstance<MessageFragment.LinkPreview>().firstOrNull()?.state?.primaryURL
    val spans = item.content.filterIsInstance<MessageFragment.Text>()
        .firstOrNull()?.payload?.formattedText?.spans.orEmpty()
    return linkActionTargets(previewUrl = previewURL, spans = spans)
}

/** Link actions from a [FormattedText] body (room bubbles have no preview URL). */
internal fun formattedTextLinkActionTargets(
    formatted: FormattedText?,
    previewUrl: String? = null,
): List<Pair<MessageLinkAccessibility.Label, LinkTarget>> =
    linkActionTargets(previewUrl = previewUrl, spans = formatted?.spans.orEmpty())

private fun linkActionTargets(
    previewUrl: String?,
    spans: List<TextSpan>,
): List<Pair<MessageLinkAccessibility.Label, LinkTarget>> =
    MessageLinkAccessibility.actions(previewUrl = previewUrl, spans = spans)
        .mapNotNull { action -> LinkTarget.parse(action.url)?.let { action.label to it } }

@Composable
private fun linkActions(
    targets: List<Pair<MessageLinkAccessibility.Label, LinkTarget>>,
    onLinkTap: (LinkTarget) -> Unit,
): List<CustomAccessibilityAction> = targets.map { (label, target) ->
    CustomAccessibilityAction(linkActionLabel(label)) {
        onLinkTap(target)
        true
    }
}

/** The localized label for a link action; the format arguments are positional-free, so they need formatting. */
@Composable
private fun linkActionLabel(label: MessageLinkAccessibility.Label): String = when (label) {
    MessageLinkAccessibility.Label.OpenLink -> stringResource(Res.string.chats_message_action_open_link)
    is MessageLinkAccessibility.Label.OpenWebLink ->
        formattedStringResource(Res.string.chats_message_action_open_web_link, label.host)

    MessageLinkAccessibility.Label.OpenMapLink -> stringResource(Res.string.chats_message_action_open_map_link)
    is MessageLinkAccessibility.Label.AddContact ->
        formattedStringResource(Res.string.chats_message_action_add_contact, label.name)

    is MessageLinkAccessibility.Label.OpenChannel ->
        formattedStringResource(Res.string.chats_message_action_open_channel, label.name)

    is MessageLinkAccessibility.Label.OpenMention ->
        formattedStringResource(Res.string.chats_message_action_open_mention, label.name)

    is MessageLinkAccessibility.Label.OpenHashtag ->
        formattedStringResource(Res.string.chats_message_action_open_hashtag, label.channel)
}
