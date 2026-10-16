package one.meshcore.shared.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.text.MentionUtilities
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_mention_accessibility_hint_channel
import one.meshcore.shared.resources.chats_mention_accessibility_hint_contact
import one.meshcore.shared.resources.chats_mention_accessibility_label
import one.meshcore.shared.resources.chats_suggestions_accessibility_label
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI

/** Matches iOS `MentionSuggestionView.maxSuggestions`. */
internal const val MENTION_SUGGESTION_LIMIT = 20

/** Matches iOS `MentionSuggestionView.maxHeight` so a long `@` list cannot crush the thread. */
internal const val MENTION_SUGGESTION_MAX_HEIGHT_DP = 200

/** Matches iOS `MentionSuggestionRow` avatar size. */
internal const val MENTION_SUGGESTION_AVATAR_DP = 32

/** Matches iOS `MentionSuggestionRow` HStack spacing and horizontal padding. */
internal const val MENTION_SUGGESTION_ROW_SPACING_DP = 12

/** Matches iOS `MentionSuggestionView.rowHeight` (32-pt avatar plus 16-pt vertical padding). */
internal const val MENTION_SUGGESTION_ROW_HEIGHT_DP = 48

/** Matches iOS `MentionSuggestionView` card corner radius. */
internal const val MENTION_SUGGESTION_CORNER_DP = 12

/** Matches iOS `ChatConversationMentionOverlay` / `MentionSuggestionView` horizontal padding. */
internal const val MENTION_SUGGESTION_HORIZONTAL_PADDING_DP = 16

/** Material overlay depth; iOS used an 8-pt 15% black drop shadow. */
internal const val MENTION_SUGGESTION_TONAL_ELEVATION_DP = 3

/** iOS overlay insert `scale(scale: 0.95, anchor: .bottom)`. */
internal const val MENTION_OVERLAY_INSERT_SCALE = 0.95f

/** iOS `ChatConversationMentionOverlay` insert/remove spring `response`. */
private const val MENTION_OVERLAY_INSERT_SPRING_RESPONSE_S = 0.3

/** iOS overlay insert/remove `dampingFraction`. */
internal const val MENTION_OVERLAY_INSERT_SPRING_DAMPING = 0.8f

/** iOS `MentionSuggestionView` height spring `response`. */
private const val MENTION_OVERLAY_HEIGHT_SPRING_RESPONSE_S = 0.25

/** iOS height spring `dampingFraction`. */
internal const val MENTION_OVERLAY_HEIGHT_SPRING_DAMPING = 0.9f

/** SwiftUI spring `response` 0.3 as Compose stiffness `(2π / response)²`. */
internal val MENTION_OVERLAY_INSERT_SPRING_STIFFNESS =
    (2.0 * PI / MENTION_OVERLAY_INSERT_SPRING_RESPONSE_S).let { it * it }.toFloat()

/** SwiftUI spring `response` 0.25 as Compose stiffness `(2π / response)²`. */
internal val MENTION_OVERLAY_HEIGHT_SPRING_STIFFNESS =
    (2.0 * PI / MENTION_OVERLAY_HEIGHT_SPRING_RESPONSE_S).let { it * it }.toFloat()

private val mentionOverlayBottomOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)

/**
 * Card height for [count] rows: 48-dp rows with no list hairlines, capped at 200 dp and 20
 * suggestions. iOS inserts 1-pt dividers between rows; Material lists do not.
 */
internal fun mentionSuggestionContentHeightDp(count: Int): Int {
    val capped = count.coerceIn(0, MENTION_SUGGESTION_LIMIT)
    if (capped == 0) return 0
    return minOf(capped * MENTION_SUGGESTION_ROW_HEIGHT_DP, MENTION_SUGGESTION_MAX_HEIGHT_DP)
}

/**
 * Freezes mention recency when the `@` query starts so an incoming channel message cannot
 * reshuffle the suggestion list. iOS `mentionSenderOrder` snapshots `channelSenderOrder` the same way.
 */
internal fun nextFrozenMentionOrder(
    queryActive: Boolean,
    frozen: Map<String, UInt>?,
    live: Map<String, UInt>,
): Map<String, UInt>? = when {
    !queryActive -> null
    frozen != null -> frozen
    else -> live
}

/**
 * Mention candidates for the active `@` token in [text], frozen against live sender-order churn
 * for the life of that token. Empty when the composer is not in a mention query.
 */
@Composable
internal fun rememberMentionSuggestions(
    text: String,
    candidates: List<ContactDto>,
    liveSenderOrder: Map<String, UInt>,
): List<ContactDto> {
    val mentionQuery = remember(text) { MentionUtilities.detectActiveMention(text) }
    var frozenSenderOrder by remember { mutableStateOf<Map<String, UInt>?>(null) }
    val queryActive = mentionQuery != null
    val nextFrozen = nextFrozenMentionOrder(queryActive, frozenSenderOrder, liveSenderOrder)
    SideEffect { frozenSenderOrder = nextFrozen }
    val orderForSuggestions = nextFrozen ?: liveSenderOrder
    return remember(mentionQuery, candidates, orderForSuggestions) {
        if (mentionQuery == null) {
            emptyList()
        } else {
            MentionUtilities
                .filterContacts(candidates, mentionQuery, orderForSuggestions.takeIf { it.isNotEmpty() })
                .take(MENTION_SUGGESTION_LIMIT)
        }
    }
}

/**
 * Floating `@` suggestions over the thread, matching iOS `ChatConversationMentionOverlay`: a material
 * card that inserts from the bottom with opacity and a 0.95 bottom-anchored scale, and removes with
 * move plus opacity. Hosts the overlay in the conversation, not inside the composer, so the bar does
 * not grow.
 */
@Composable
fun MentionSuggestionOverlay(
    suggestions: List<ContactDto>,
    onSelect: (ContactDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val enterFloat = spring<Float>(
        dampingRatio = MENTION_OVERLAY_INSERT_SPRING_DAMPING,
        stiffness = MENTION_OVERLAY_INSERT_SPRING_STIFFNESS,
    )
    val enterOffset = spring<IntOffset>(
        dampingRatio = MENTION_OVERLAY_INSERT_SPRING_DAMPING,
        stiffness = MENTION_OVERLAY_INSERT_SPRING_STIFFNESS,
    )
    val enter = if (reduceMotion) {
        fadeIn(animationSpec = snap())
    } else {
        fadeIn(animationSpec = enterFloat) +
            slideInVertically(animationSpec = enterOffset) { it } +
            scaleIn(
                animationSpec = enterFloat,
                initialScale = MENTION_OVERLAY_INSERT_SCALE,
                transformOrigin = mentionOverlayBottomOrigin,
            )
    }
    val exit = if (reduceMotion) {
        fadeOut(animationSpec = snap())
    } else {
        fadeOut(animationSpec = enterFloat) +
            slideOutVertically(animationSpec = enterOffset) { it }
    }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter = enter,
            exit = exit,
        ) {
            MentionSuggestionCard(
                contacts = suggestions,
                onSelect = onSelect,
                reduceMotion = reduceMotion,
                modifier = Modifier.padding(horizontal = MENTION_SUGGESTION_HORIZONTAL_PADDING_DP.dp),
            )
        }
    }
}

/**
 * Port of iOS `MentionSuggestionView`: 12-pt corners, height cap 200, Material tonal surface.
 */
@Composable
private fun MentionSuggestionCard(
    contacts: List<ContactDto>,
    onSelect: (ContactDto) -> Unit,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val suggestionsLabel = stringResource(Res.string.chats_suggestions_accessibility_label)
    val targetHeight = mentionSuggestionContentHeightDp(contacts.size).dp
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = MENTION_OVERLAY_HEIGHT_SPRING_DAMPING,
                stiffness = MENTION_OVERLAY_HEIGHT_SPRING_STIFFNESS,
            )
        },
        label = "mentionSuggestionHeight",
    )
    val cardShape = RoundedCornerShape(MENTION_SUGGESTION_CORNER_DP.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .semantics { contentDescription = suggestionsLabel },
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = MENTION_SUGGESTION_TONAL_ELEVATION_DP.dp,
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(contacts, key = { contact -> contact.id.value }) { contact ->
                MentionSuggestionRow(contact = contact, onSelect = { onSelect(contact) })
            }
        }
    }
}

/**
 * One mention candidate. The row speaks as a single "Mention <name>" node, with the hint distinguishing a
 * saved contact from a name heard only on a channel — a channel sender is never key-verified, so mentioning
 * one is not the same act as mentioning a contact. Port of the iOS `MentionSuggestionRow`.
 */
@Composable
private fun MentionSuggestionRow(contact: ContactDto, onSelect: () -> Unit) {
    val label = formattedStringResource(Res.string.chats_mention_accessibility_label, contact.displayName)
    val hint = if (contact.publicKey.isEmpty()) {
        stringResource(Res.string.chats_mention_accessibility_hint_channel)
    } else {
        stringResource(Res.string.chats_mention_accessibility_hint_contact)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MENTION_SUGGESTION_ROW_HEIGHT_DP.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = MENTION_SUGGESTION_ROW_SPACING_DP.dp)
            .semantics {
                contentDescription = label
                stateDescription = hint
            },
        horizontalArrangement = Arrangement.spacedBy(MENTION_SUGGESTION_ROW_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact = contact, size = MENTION_SUGGESTION_AVATAR_DP.dp)
        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
