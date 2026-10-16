package one.meshcore.shared.contact.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.ui.icons.AppIcons

internal fun blockToggleIcon(isBlocked: Boolean): ImageVector =
    if (isBlocked) AppIcons.DoNotTouch else AppIcons.FrontHand
