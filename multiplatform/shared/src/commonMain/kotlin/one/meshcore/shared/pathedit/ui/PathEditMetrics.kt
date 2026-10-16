package one.meshcore.shared.pathedit.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout constants shared across the path-editing sheet and the Add-Hop picker, ported 1:1 (pt → dp)
 * from the iOS `PathEditMetrics`. [tapTarget] is glove-safe, above Material's 48dp minimum.
 */
internal object PathEditMetrics {
    val tapTarget: Dp = 48.dp
    val rowInset: Dp = 16.dp
    val rowVerticalPadding: Dp = 8.dp
    val rowContentSpacing: Dp = 12.dp
    val badgeSpacing: Dp = 6.dp
    val ctaIconSize: Dp = 22.dp
    val badgeHorizontalPadding: Dp = 6.dp
    val badgeVerticalPadding: Dp = 2.dp
    const val BADGE_BACKGROUND_ALPHA: Float = 0.15f

    /** How long the add-hop trailing checkmark stays up, matching iOS `PickerRowView`. */
    const val ADD_HOP_SUCCESS_MS = 1_500L
}
