package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.ui.icons.AppIcons

/** iOS mention-picker self: `person.crop.circle`; not-saved / Send DM: `person.crop.circle.badge.questionmark`. */
internal fun mentionPickerEmptyIcon(isSelfMention: Boolean): ImageVector =
    if (isSelfMention) AppIcons.AccountCircle else AppIcons.PersonOff

internal val SEND_DM_NO_MATCHES_ICON: ImageVector get() = mentionPickerEmptyIcon(isSelfMention = false)
