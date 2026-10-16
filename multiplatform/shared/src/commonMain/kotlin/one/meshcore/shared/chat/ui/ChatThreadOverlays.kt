package one.meshcore.shared.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_common_ok
import one.meshcore.shared.resources.chats_error_banner_dismiss_accessibility_hint
import one.meshcore.shared.resources.chats_scroll_button_badge_overflow
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_bottom_accessibility_label
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_bottom_accessibility_value
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_divider_accessibility_hint
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_divider_accessibility_label
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_mention_accessibility_hint
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_mention_accessibility_label
import one.meshcore.shared.resources.chats_scroll_button_scroll_to_mention_accessibility_value
import one.meshcore.shared.resources.localizable_common_close
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.resources.settings_alert_error_title
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

/**
 * The scroll-to-bottom affordance shown while the viewport is scrolled up (spec §9.5): a tonal button with
 * an unread count badge. Tapping it requests the state-driven scroll to the newest row rather than the app
 * yanking the viewport on each arrival. Opacity plus 0.5→1 scale matches iOS `ScrollToBottomButton`.
 */
@Composable
fun ScrollToBottomAffordance(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val spec = if (reduceMotion) {
        snap()
    } else {
        tween<Float>(SCROLL_TO_BOTTOM_ANIM_MS)
    }
    AnimatedVisibility(
        visible = isVisible,
        modifier = modifier,
        enter = if (reduceMotion) {
            fadeIn(spec)
        } else {
            fadeIn(spec) + scaleIn(spec, initialScale = SCROLL_TO_BOTTOM_HIDDEN_SCALE)
        },
        exit = if (reduceMotion) {
            fadeOut(spec)
        } else {
            fadeOut(spec) + scaleOut(spec, targetScale = SCROLL_TO_BOTTOM_HIDDEN_SCALE)
        },
    ) {
        val label = stringResource(Res.string.chats_scroll_button_scroll_to_bottom_accessibility_label)
        val value =
            formattedStringResource(Res.string.chats_scroll_button_scroll_to_bottom_accessibility_value, unreadCount)
        val overflow = stringResource(Res.string.chats_scroll_button_badge_overflow)
        BadgedBox(
            modifier = Modifier.semantics {
                contentDescription = label
                if (unreadCount > 0) stateDescription = value
            },
            badge = {
                if (unreadCount > 0) {
                    Badge { Text(if (unreadCount > BADGE_MAX) overflow else unreadCount.toString()) }
                }
            },
        ) {
            FilledTonalIconButton(onClick = onClick) {
                Icon(
                    imageVector = AppIcons.ExpandMore,
                    contentDescription = null,
                )
            }
        }
    }
}

/**
 * The jump-to-divider affordance (spec §9.4 / iOS `ScrollToDividerButton`): shown while the new-messages
 * divider is placed and scrolled out of view. A plain icon button (no badge) since there is exactly one
 * divider per thread open.
 */
@Composable
fun ScrollToDividerAffordance(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.chats_scroll_button_scroll_to_divider_accessibility_label)
    val hint = stringResource(Res.string.chats_scroll_button_scroll_to_divider_accessibility_hint)
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = hint
        },
    ) {
        Icon(imageVector = AppIcons.KeyboardArrowUp, contentDescription = null)
    }
}

/**
 * The scroll-to-mention affordance (iOS `ScrollToMentionButton`): shown while one or more self-mentions
 * have not yet scrolled into view, badged with the off-screen count. Tapping it jumps to the oldest one.
 */
@Composable
fun ScrollToMentionAffordance(unseenCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.chats_scroll_button_scroll_to_mention_accessibility_label)
    val hint = stringResource(Res.string.chats_scroll_button_scroll_to_mention_accessibility_hint)
    val value = formattedStringResource(
        Res.string.chats_scroll_button_scroll_to_mention_accessibility_value,
        unseenCount,
    )
    val overflow = stringResource(Res.string.chats_scroll_button_badge_overflow)
    BadgedBox(
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = "$hint. $value"
        },
        badge = { Badge { Text(if (unseenCount > BADGE_MAX) overflow else unseenCount.toString()) } },
    ) {
        FilledTonalIconButton(onClick = onClick) {
            Icon(imageVector = AppIcons.AlternateEmail, contentDescription = null)
        }
    }
}

/**
 * Dismissible strip for a background failure (older-page load). Sits between
 * the list and the composer.
 */
@Composable
fun ThreadErrorBanner(message: String, onDismiss: () -> Unit) {
    val hint = stringResource(Res.string.chats_error_banner_dismiss_accessibility_hint)
    val closeLabel = stringResource(Res.string.localizable_common_close)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics {
                contentDescription = message
                stateDescription = hint
            },
        shape = MaterialTheme.shapes.medium,
        color = colors.errorContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = colors.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = closeLabel,
                    tint = colors.onErrorContainer,
                )
            }
        }
    }
}

/** A single-message error alert, matching iOS `errorAlert` (title defaults to Error). */
@Composable
fun ThreadErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    dialogTitle: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val title = dialogTitle ?: stringResource(Res.string.settings_alert_error_title)
    if (onRetry == null) {
        AcknowledgedAlertDialog(
            title = title,
            message = message,
            confirmLabel = stringResource(Res.string.chats_common_ok),
            onDismiss = onDismiss,
        )
        return
    }
    ConfirmAlertDialog(
        title = title,
        message = message,
        confirmLabel = stringResource(Res.string.localizable_common_try_again),
        dismissLabel = stringResource(Res.string.chats_common_ok),
        onConfirm = {
            onDismiss()
            onRetry()
        },
        onDismiss = onDismiss,
    )
}

private const val BADGE_MAX = 99

/** iOS `ScrollToBottomButton` `.animation(.snappy(duration: 0.2), value: isVisible)`. */
internal const val SCROLL_TO_BOTTOM_ANIM_MS = 200

/** iOS `ScrollToBottomButton` hidden `scaleEffect`. */
internal const val SCROLL_TO_BOTTOM_HIDDEN_SCALE = 0.5f
