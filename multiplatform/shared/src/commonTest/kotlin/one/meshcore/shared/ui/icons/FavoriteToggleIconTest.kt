package one.meshcore.shared.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FavoriteToggleIconTest {
    @Test
    fun unfavoriteUsesStarSlashNotBorder() {
        assertEquals(AppIcons.StarSlash, favoriteToggleIcon(isFavorite = true))
        assertNotEquals(AppIcons.StarBorder, favoriteToggleIcon(isFavorite = true))
    }

    @Test
    fun favoriteUsesFilledStar() {
        assertEquals(AppIcons.Star, favoriteToggleIcon(isFavorite = false))
    }

    @Test
    fun mapFavoriteUsesOutlineStarNotFill() {
        assertEquals(AppIcons.StarSlash, mapFavoriteToggleIcon(isFavorite = true))
        assertEquals(AppIcons.StarBorder, mapFavoriteToggleIcon(isFavorite = false))
    }
}
