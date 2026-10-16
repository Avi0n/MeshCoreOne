package one.meshcore.shared.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.reactions.ReactionAggregation
import one.meshcore.shared.chat.reactions.ReactionDetail
import one.meshcore.shared.chat.reactions.ReactionDetailGroup
import one.meshcore.shared.chat.reactions.ReactionStore
import one.meshcore.shared.chat.reactions.emojiAccessibilityName
import one.meshcore.shared.chat.reactions.loadReactionDetails
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_reactions_empty_state_description
import one.meshcore.shared.resources.chats_reactions_empty_state_title
import one.meshcore.shared.resources.chats_reactions_title
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.namedRelativeLabel
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import org.jetbrains.compose.resources.stringResource

/**
 * Who-reacted form for a message: loads rows from [ReactionStore], groups via [ReactionAggregation.details],
 * shows emoji tabs and a sender list. Empty state when no reactions.
 *
 * [initialEmoji] is the badge that opened this overlay (iOS `ReactionDetailsSheet.initialEmoji`).
 * Overflow, TalkBack "view details", and a vanished reaction fall back to the first tab.
 */
@Composable
fun ReactionDetailsSheet(
    messageId: Uuid,
    reactionStore: ReactionStore?,
    onDismiss: () -> Unit,
    initialEmoji: String? = null,
    modifier: Modifier = Modifier,
) {
    var groups by remember { mutableStateOf<List<ReactionDetailGroup>>(emptyList()) }
    var selectedEmoji by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val nowSeconds by rememberMinuteTickSeconds()

    LaunchedEffect(messageId, initialEmoji, reactionStore) {
        isLoading = true
        groups = emptyList()
        selectedEmoji = null
        if (reactionStore == null) {
            isLoading = false
            return@LaunchedEffect
        }
        val details = loadReactionDetails { reactionStore.fetchReactions(messageId) }.map {
            ReactionDetail(emoji = it.emoji, senderName = it.senderName, receivedAt = it.receivedAt)
        }
        val aggregated = ReactionAggregation.details(details)
        groups = aggregated
        selectedEmoji = resolvedReactionSelection(
            preferred = initialEmoji,
            available = aggregated.map { it.emoji },
        )
        isLoading = false
    }

    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.chats_reactions_title),
        modifier = modifier,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                groups.isEmpty() -> {
                    ChatDetailEmptyPane(
                        title = stringResource(Res.string.chats_reactions_empty_state_title),
                        description = stringResource(Res.string.chats_reactions_empty_state_description),
                        icon = AppIcons.SentimentSatisfied,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    EmojiTabs(
                        groups = groups,
                        selectedEmoji = selectedEmoji,
                        onSelect = { selectedEmoji = it },
                    )
                    val entries = groups.firstOrNull { it.emoji == selectedEmoji }?.entries.orEmpty()
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(entries, key = { "${it.senderName}-${it.receivedAt}" }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.senderName) },
                                supportingContent = {
                                    Text(
                                        text = namedRelativeLabel(entry.receivedAt.toLong(), nowSeconds),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Uses [preferred] when it is still in [available]; otherwise the first group so overflow
 * and a vanished reaction still land on a tab. iOS `ReactionDetailsSheet.resolvedSelection`.
 */
fun resolvedReactionSelection(preferred: String?, available: List<String>): String? {
    if (preferred != null && preferred in available) return preferred
    return available.firstOrNull()
}

@Composable
private fun EmojiTabs(groups: List<ReactionDetailGroup>, selectedEmoji: String?, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            val selected = group.emoji == selectedEmoji
            val spoken = "${emojiAccessibilityName(group.emoji)}, ${group.count}"
            FilterChip(
                selected = selected,
                onClick = { onSelect(group.emoji) },
                label = { Text("${group.emoji} ${group.count}") },
                modifier = Modifier.semantics { contentDescription = spoken },
            )
        }
    }
}
