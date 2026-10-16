package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.NodeNameResolution

/**
 * The already-localized fragments a composed message label can draw on. Every slot is resolved by the
 * caller whether or not the message needs it, so the inclusion and ordering rules stay in one pure
 * function instead of spreading across the renderer. Mirrors the string set the Swift
 * `UnifiedMessageBubble.accessibilityMessageLabel` reads.
 *
 * [unverifiedNicknameSuffix] is the parenthesized wire name (`"(NodeName)"`), [status] the outgoing
 * delivery state, and [hopCount] / [path] / [region] the incoming routing metadata, each already
 * substituted with its value.
 */
data class MessageAccessibilityStrings(
    val unverifiedNicknameSuffix: String,
    val unverifiedNicknameWarning: String,
    val possibleMatch: String,
    val status: String,
    val hopCount: String,
    val path: String,
    val region: String,
)

/**
 * The whole message spoken as one phrase: sender with its trust qualifier, the message text, then the
 * outgoing delivery status or the incoming hop / path / region metadata. A screen reader that walks the
 * footer glyph by glyph never reaches the message itself, so the bubble merges into a single node and
 * speaks this. Mirrors the Swift `UnifiedMessageBubble.accessibilityMessageLabel` element for element.
 *
 * The sender prefix rides on every incoming channel message, not only the first of a cluster: a
 * screen-reader user has no visual grouping to carry the name forward.
 */
fun messageAccessibilityLabel(
    item: MessageItem,
    strings: MessageAccessibilityStrings,
    spokenBody: String? = null,
): String {
    val builder = StringBuilder()
    val envelope = item.envelope
    val footer = item.footer
    val incoming = !envelope.isOutgoing
    if (incoming && footer.isChannelMessage && envelope.senderName.isNotEmpty()) {
        builder.append(senderPrefix(envelope.senderResolution, envelope.senderName, strings))
    }
    builder.append(spokenBody ?: messageText(item))
    if (envelope.isOutgoing) {
        builder.append(", ").append(strings.status)
    } else {
        if (footer.showHop) builder.append(", ").append(strings.hopCount)
        if (!footer.formattedPath.isNullOrEmpty()) builder.append(", ").append(strings.path)
        if (!footer.regionToShow.isNullOrEmpty()) builder.append(", ").append(strings.region)
    }
    return builder.toString()
}

/** The message body a screen reader reads: the text fragment's raw source, empty when there is none. */
private fun messageText(item: MessageItem): String =
    item.content.filterIsInstance<MessageFragment.Text>().firstOrNull()?.payload?.raw.orEmpty()

/**
 * "Name: " for a verified sender, with the unverified-nickname or possible-match qualifier appended when
 * the identity behind the name was asserted or guessed rather than matched by key.
 */
private fun senderPrefix(
    resolution: NodeNameResolution,
    senderName: String,
    strings: MessageAccessibilityStrings,
): String = when (senderLabelTrust(resolution)) {
    SenderLabelTrust.UNVERIFIED_NICKNAME ->
        "${resolution.unverifiedNickname} ${strings.unverifiedNicknameSuffix}: ${strings.unverifiedNicknameWarning}, "
    SenderLabelTrust.POSSIBLE_MATCH -> "$senderName: ${strings.possibleMatch}, "
    SenderLabelTrust.PLAIN -> "$senderName: "
}
