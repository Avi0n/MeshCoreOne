package one.meshcore.shared.contact.ui

import one.meshcore.shared.ui.FAVORITE_STAR_ROLE
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactDetailActionIconTest {

    @Test
    fun favoriteUsesOutlineStar() {
        assertEquals(AppIcons.StarBorder, contactDetailFavoriteIcon(isFavorite = false))
        assertEquals(AppIcons.Star, contactDetailFavoriteIcon(isFavorite = true))
        assertEquals(SignalChromeRole.MUTED, contactDetailFavoriteRole(isFavorite = false))
        assertEquals(SignalChromeRole.TERTIARY, contactDetailFavoriteRole(isFavorite = true))
        assertEquals(FAVORITE_STAR_ROLE, contactDetailFavoriteRole(isFavorite = true))
    }
}
