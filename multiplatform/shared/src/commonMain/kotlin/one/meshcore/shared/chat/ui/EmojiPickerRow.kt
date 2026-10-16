package one.meshcore.shared.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.meshcore.shared.chat.reactions.emojiAccessibilityName
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_reactions_more_emojis
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Quick-access reaction emoji strip plus a more control. Uses Material 3 filled-tonal icon buttons.
 * The strip is centered when it fits, and scrolls horizontally when it overflows.
 */
@Composable
fun EmojiPickerRow(
    emojis: List<String>,
    onSelect: (String) -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val contentWidth = emojiPickerRowContentWidthDp(emojis.size)
        val fits = contentWidth.dp <= maxWidth
        val rowModifier = if (fits) {
            Modifier.align(Alignment.Center)
        } else {
            Modifier.horizontalScroll(rememberScrollState())
        }
        EmojiButtonRow(
            emojis = emojis,
            onSelect = onSelect,
            onOpenMore = onOpenMore,
            modifier = rowModifier.padding(horizontal = EMOJI_PICKER_HORIZONTAL_PADDING_DP.dp),
        )
    }
}

@Composable
private fun EmojiButtonRow(
    emojis: List<String>,
    onSelect: (String) -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    val moreLabel = stringResource(Res.string.chats_reactions_more_emojis)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(EMOJI_PICKER_SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emojis.forEach { emoji ->
            val spoken = emojiAccessibilityName(emoji)
            FilledTonalIconButton(
                onClick = {
                    haptics.tap()
                    onSelect(emoji)
                },
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            ) {
                Text(text = emoji, fontSize = EMOJI_GLYPH_SP.sp)
            }
        }
        FilledTonalIconButton(
            onClick = onOpenMore,
            modifier = Modifier.clearAndSetSemantics { contentDescription = moreLabel },
        ) {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = null,
            )
        }
    }
}

/** Default Material 3 filled-tonal icon-button container size. */
internal const val EMOJI_PICKER_BUTTON_DP = 40

/** Gap between tonal reaction buttons. */
internal const val EMOJI_PICKER_SPACING_DP = 8

/** Horizontal inset around the strip. */
internal const val EMOJI_PICKER_HORIZONTAL_PADDING_DP = 16

private const val EMOJI_GLYPH_SP = 18

/**
 * Ideal width of the emoji strip including horizontal padding: tonal buttons, gaps, plus the
 * more control. The strip scrolls when this exceeds the available width.
 */
internal fun emojiPickerRowContentWidthDp(emojiCount: Int): Int {
    val buttons = emojiCount + 1
    if (buttons <= 0) return EMOJI_PICKER_HORIZONTAL_PADDING_DP * 2
    return EMOJI_PICKER_HORIZONTAL_PADDING_DP * 2 +
        buttons * EMOJI_PICKER_BUTTON_DP +
        (buttons - 1) * EMOJI_PICKER_SPACING_DP
}
