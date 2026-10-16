package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import one.meshcore.shared.chat.InlineImage
import one.meshcore.shared.chat.MessageFragment
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_retry
import one.meshcore.shared.resources.chats_message_action_retry_image
import one.meshcore.shared.resources.chats_message_action_show_original
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_action_view_reactions
import one.meshcore.shared.resources.chats_message_hop_count_accessibility_label
import one.meshcore.shared.resources.chats_message_path_accessibility_label
import one.meshcore.shared.resources.chats_message_region_accessibility_label
import one.meshcore.shared.resources.chats_message_region_ambiguous_accessibility_label
import one.meshcore.shared.resources.chats_message_sender_possible_match
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_accessibility_label
import one.meshcore.shared.resources.chats_message_sender_unverified_nickname_format
import one.meshcore.shared.resources.chats_preview_tap_to_load
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Resolves every localized fragment the composed bubble label can use, whether or not this message needs
 * it, so the inclusion rules stay in the pure [messageAccessibilityLabel] rather than in a chain of
 * conditional `stringResource` calls the renderer would have to keep in step with iOS.
 */
@Composable
internal fun rememberMessageAccessibilityStrings(item: MessageItem): MessageAccessibilityStrings =
    MessageAccessibilityStrings(
        unverifiedNicknameSuffix = formattedStringResource(
            Res.string.chats_message_sender_unverified_nickname_format,
            item.envelope.senderResolution.displayName,
        ),
        unverifiedNicknameWarning = stringResource(
            Res.string.chats_message_sender_unverified_nickname_accessibility_label,
        ),
        possibleMatch = stringResource(Res.string.chats_message_sender_possible_match),
        status = statusAccessibilityLabel(item.footer),
        hopCount = formattedStringResource(
            Res.string.chats_message_hop_count_accessibility_label,
            item.footer.hopCount,
        ),
        path = formattedStringResource(
            Res.string.chats_message_path_accessibility_label,
            item.footer.formattedPath.orEmpty(),
        ),
        region = if (item.footer.regionMatchNames.size > 1) {
            formattedStringResource(
                Res.string.chats_message_region_ambiguous_accessibility_label,
                item.footer.regionMatchNames.joinToString(", "),
            )
        } else {
            formattedStringResource(
                Res.string.chats_message_region_accessibility_label,
                item.footer.regionToShow.orEmpty(),
            )
        },
    )

/**
 * The screen-reader route to what the bubble otherwise only offers by touch: retrying a failed send,
 * opening the reaction roster, the inline image's retry / load affordance, and one action per link the
 * message carries. Mirrors the iOS `accessibilityActions` set. The body text is one merged node, so
 * without the link actions a screen-reader user cannot activate any link, mention, hashtag or coordinate
 * in a message. Viewing a loaded image is not among them — that viewer is owned by [InlineImageFragment]
 * and its image is already its own focusable node. The actions sheet stays on the row's long press, which
 * TalkBack surfaces on its own.
 */
@Composable
internal fun messageAccessibilityActions(
    item: MessageItem,
    onRetry: () -> Unit,
    onOpenReactionDetails: () -> Unit,
    onManualPreviewFetch: () -> Unit,
    onRetryPreviewFetch: () -> Unit,
    onLinkTap: (LinkTarget) -> Unit,
    translationPhase: MessageTranslationPhase?,
    onTranslate: () -> Unit,
): List<CustomAccessibilityAction> {
    val linkActions = messageLinkActions(item, onLinkTap)
    val retry = stringResource(Res.string.chats_message_action_retry)
    val viewReactions = stringResource(Res.string.chats_message_action_view_reactions)
    val retryImage = stringResource(Res.string.chats_message_action_retry_image)
    val loadImage = stringResource(Res.string.chats_preview_tap_to_load)
    val translate = stringResource(Res.string.chats_message_action_translate)
    val showOriginal = stringResource(Res.string.chats_message_action_show_original)
    return buildList {
        when (translationPhase) {
            MessageTranslationPhase.Offer ->
                add(
                    CustomAccessibilityAction(translate) {
                        onTranslate()
                        true
                    },
                )
            is MessageTranslationPhase.Showing ->
                add(
                    CustomAccessibilityAction(showOriginal) {
                        onTranslate()
                        true
                    },
                )
            else -> Unit
        }
        if (item.footer.showStatusRow && item.footer.status == MessageStatus.FAILED) {
            add(
                CustomAccessibilityAction(retry) {
                    onRetry()
                    true
                },
            )
        }
        if (item.content.any { it is MessageFragment.ReactionSummary }) {
            add(
                CustomAccessibilityAction(viewReactions) {
                    onOpenReactionDetails()
                    true
                },
            )
        }
        when (item.content.filterIsInstance<MessageFragment.InlineImage>().firstOrNull()?.image?.state) {
            is InlineImage.LoadState.Failed ->
                add(
                    CustomAccessibilityAction(retryImage) {
                        onRetryPreviewFetch()
                        true
                    },
                )
            is InlineImage.LoadState.Disabled ->
                add(
                    CustomAccessibilityAction(loadImage) {
                        onManualPreviewFetch()
                        true
                    },
                )
            else -> Unit
        }
        addAll(linkActions)
    }
}
