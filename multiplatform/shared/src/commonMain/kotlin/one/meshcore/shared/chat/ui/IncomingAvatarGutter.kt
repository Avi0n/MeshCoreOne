package one.meshcore.shared.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.chat.IncomingAvatarJPEGStore
import one.meshcore.shared.contact.ui.ContactAvatar
import one.meshcore.shared.model.Uuid

/**
 * Leading column for incoming channel rows. The avatar sits on the cluster-end row; earlier rows in
 * the same sender run keep the empty column so bubbles stay aligned. Mirrors iOS
 * `IncomingAvatarGutter` without the fly animation.
 */
@Composable
fun IncomingAvatarGutter(
    showAvatar: Boolean,
    name: String,
    modifier: Modifier = Modifier,
    matchedContactID: Uuid? = null,
) {
    Box(
        modifier = modifier
            .size(width = INCOMING_AVATAR_COLUMN_WIDTH_DP.dp, height = INCOMING_AVATAR_SIZE_DP.dp)
            .padding(end = INCOMING_AVATAR_GAP_DP.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (showAvatar) {
            Box(Modifier.clearAndSetSemantics { }) {
                ContactAvatar(
                    name = name,
                    size = INCOMING_AVATAR_SIZE_DP.dp,
                    imageData = IncomingAvatarJPEGStore.data(matchedContactID),
                )
            }
        }
    }
}

/** iOS `IncomingBubbleAvatarMetrics.size`. */
internal const val INCOMING_AVATAR_SIZE_DP = 28

/** iOS `IncomingBubbleAvatarMetrics.gap`. */
internal const val INCOMING_AVATAR_GAP_DP = 6

internal const val INCOMING_AVATAR_COLUMN_WIDTH_DP = INCOMING_AVATAR_SIZE_DP + INCOMING_AVATAR_GAP_DP
