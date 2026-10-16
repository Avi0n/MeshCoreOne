package one.meshcore.shared.theme

import one.meshcore.shared.avatar.AvatarCategory
import one.meshcore.shared.avatar.IdentityGamut
import one.meshcore.shared.avatar.RgbColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `avatarGlyphColor`: System override is always white; gamut fills follow luminance. */
class CategoryAvatarGlyphColorTest {
    @Test
    fun systemOverrideKeepsWhiteEvenOnALightFill() {
        val theme = ThemeRegistry.byId(AppTheme.DEFAULT_ID)
        assertTrue(theme.usesCategoryAvatarOverride)
        val light = RgbColor(0.95, 0.95, 0.95)
        assertFalse(IdentityGamut.glyphIsWhite(light.luminance))
        assertTrue(theme.avatarGlyphIsWhite(light))
    }

    @Test
    fun fernFollowsFillLuminance() {
        val theme = ThemeRegistry.byId("fern")
        assertFalse(theme.usesCategoryAvatarOverride)
        val fill = theme.categoryColor(AvatarCategory.CHANNEL, useDark = true)
        assertEquals(IdentityGamut.glyphIsWhite(fill.luminance), theme.avatarGlyphIsWhite(fill))
        val light = RgbColor(0.95, 0.95, 0.95)
        assertFalse(theme.avatarGlyphIsWhite(light))
    }
}
