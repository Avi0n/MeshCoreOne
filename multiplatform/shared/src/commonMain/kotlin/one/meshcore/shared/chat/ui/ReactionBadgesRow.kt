package one.meshcore.shared.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.reactions.ReactionAggregation
import one.meshcore.shared.chat.reactions.ReactionConstants
import one.meshcore.shared.chat.reactions.emojiAccessibilityName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_reactions_badge
import one.meshcore.shared.resources.chats_reactions_badge_hint
import one.meshcore.shared.resources.chats_reactions_more_badge
import one.meshcore.shared.resources.chats_reactions_more_badge_hint
import one.meshcore.shared.resources.chats_reactions_view_details
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

private const val BADGE_CORNER_DP = 10
private const val BADGE_H_PAD_DP = 8
private const val BADGE_V_PAD_DP = 4
private const val BADGE_GAP_DP = 4

/**
 * High-contrast reaction badge row under a bubble body, rendered from the raw summary string
 * (`"👍:3,❤️:2"`). Overflow beyond [ReactionConstants.BADGE_PREFIX_COUNT] shows a `+N` chip. No low-contrast
 * glass — solid surfaceVariant chips for outdoor readability. Tapping a visible badge opens who-reacted
 * on that emoji's tab; tapping overflow or long-pressing the row opens the same overlay on the first tab.
 * Mirrors iOS `ReactionBadgesView.onSelect`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionBadgesRow(
    summary: String,
    modifier: Modifier = Modifier,
    onTapReaction: (String) -> Unit = {},
    onOpenDetails: () -> Unit = {},
) {
    val badges = ReactionAggregation.badges(summary)
    if (badges.visible.isEmpty()) return
    val haptics = rememberAppHaptics()
    val openDetailsFromLongPress: () -> Unit = {
        haptics.tap()
        onOpenDetails()
    }
    val badgeHint = stringResource(Res.string.chats_reactions_badge_hint)
    val moreBadgeHint = stringResource(Res.string.chats_reactions_more_badge_hint)
    val viewDetails = stringResource(Res.string.chats_reactions_view_details)
    Row(
        modifier = modifier
            .combinedClickable(onClick = onOpenDetails, onLongClick = openDetailsFromLongPress)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(viewDetails) {
                        onOpenDetails()
                        true
                    },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(BADGE_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in badges.visible) {
            val spoken = formattedStringResource(
                Res.string.chats_reactions_badge,
                emojiAccessibilityName(item.emoji),
                item.count,
            ) + ". $badgeHint"
            // iOS ReactionBadge hides the number for a single reaction, showing the emoji alone.
            Text(
                text = if (item.count > 1) "${item.emoji} ${item.count}" else item.emoji,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .semantics { contentDescription = spoken }
                    .clip(RoundedCornerShape(BADGE_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClick = { onTapReaction(item.emoji) },
                        onLongClick = openDetailsFromLongPress,
                    )
                    .padding(horizontal = BADGE_H_PAD_DP.dp, vertical = BADGE_V_PAD_DP.dp),
            )
        }
        if (badges.overflowCount > 0) {
            val overflowSpoken = formattedStringResource(
                Res.string.chats_reactions_more_badge,
                badges.overflowCount,
            ) + ". $moreBadgeHint"
            Text(
                text = "+${badges.overflowCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { contentDescription = overflowSpoken }
                    .clip(RoundedCornerShape(BADGE_CORNER_DP.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = onOpenDetails, onLongClick = openDetailsFromLongPress)
                    .padding(horizontal = BADGE_H_PAD_DP.dp, vertical = BADGE_V_PAD_DP.dp),
            )
        }
    }
}
