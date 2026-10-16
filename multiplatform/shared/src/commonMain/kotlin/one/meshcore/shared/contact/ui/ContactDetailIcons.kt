package one.meshcore.shared.contact.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.ui.FAVORITE_STAR_ROLE
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.icons.AppIcons

internal fun contactDetailFavoriteIcon(isFavorite: Boolean): ImageVector =
    if (isFavorite) AppIcons.Star else AppIcons.StarBorder

internal fun contactDetailFavoriteRole(isFavorite: Boolean): SignalChromeRole =
    if (isFavorite) FAVORITE_STAR_ROLE else SignalChromeRole.MUTED
