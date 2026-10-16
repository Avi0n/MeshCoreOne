package one.meshcore.shared.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the Swift avatar/contrast suites (`IdentityGamutTests`, `ThemeContrastTests`) against the
 * Kotlin port: determinism, hue spread, WCAG-AA legibility against every surface in both appearances and
 * contrasts, distinct category anchors, and glyph legibility. The golden ARGB vectors are captured from
 * the identical pure-Double Swift math (same gamut, same surface luminances) so any drift is caught.
 */
class IdentityGamutTest {
    // The System theme's gamut, exercised directly the way the Swift IdentityGamutTests use a gamut.
    private val gamut = NeutralAvatarPalette.gamut

    private val names: List<String> =
        (0 until NAME_SPREAD).map { "Sender $it" } + listOf("Bob", "obB", "Alice", "alice", "灯火", "Søren", "#general")

    private fun surfaces(dark: Boolean): List<Double> = if (dark) {
        listOf(WcagContrast.relativeLuminance(0.0, 0.0, 0.0), WcagContrast.relativeLuminance(0.11, 0.11, 0.12))
    } else {
        listOf(WcagContrast.relativeLuminance(1.0, 1.0, 1.0), WcagContrast.relativeLuminance(0.95, 0.95, 0.96))
    }

    @Test
    fun sameNameAlwaysResolvesToTheSameColor() {
        for (dark in listOf(false, true)) {
            val first = NeutralAvatarPalette.identityColor("Repeater Bravo", dark)
            repeat(5) {
                assertEquals(first, NeutralAvatarPalette.identityColor("Repeater Bravo", dark))
            }
        }
    }

    @Test
    fun hueIsStableAcrossAppearanceAndContrast() {
        for (name in names.take(50)) {
            val lightHue = gamut.resolve(name, surfaces(dark = false), highContrast = false).hue
            val darkHue = gamut.resolve(name, surfaces(dark = true), highContrast = false).hue
            val hcHue = gamut.resolve(name, surfaces(dark = false), highContrast = true).hue
            assertEquals(lightHue, darkHue, "hue drifted across appearance for $name")
            assertEquals(lightHue, hcHue, "hue drifted across contrast for $name")
        }
    }

    @Test
    fun distinctNamesSpreadAcrossManyHues() {
        val hues = names.map { gamut.resolve(it, surfaces(dark = false), highContrast = false).hue }.toSet()
        assertTrue(hues.size > MIN_DISTINCT_HUES, "hues collapsed to ${hues.size} distinct values")
    }

    @Test
    fun everyIdentityColorClearsAaAgainstItsSurfacesInBothAppearancesAndContrasts() {
        for (dark in listOf(false, true)) {
            for (highContrast in listOf(false, true)) {
                names.forEach { assertClearsFloor(it, surfaces(dark), highContrast) }
            }
        }
    }

    private fun assertClearsFloor(name: String, backgrounds: List<Double>, highContrast: Boolean) {
        val floor = if (highContrast) WcagContrast.INCREASED_CONTRAST_FLOOR else WcagContrast.AA_FLOOR
        val fill = gamut.colorForName(name, backgrounds, highContrast)
        for (background in backgrounds) {
            val ratio = WcagContrast.contrastRatio(fill.luminance, background)
            assertTrue(ratio >= floor, "$name fill vs $background is $ratio, below $floor")
        }
    }

    @Test
    fun avatarGlyphClearsAaAgainstTheIdentityFillInBothAppearances() {
        for (dark in listOf(false, true)) {
            for (name in names) {
                val fill = gamut.colorForName(name, surfaces(dark), highContrast = false)
                val glyphLuminance = if (IdentityGamut.glyphIsWhite(fill.luminance)) {
                    WcagContrast.relativeLuminance(1.0, 1.0, 1.0)
                } else {
                    WcagContrast.relativeLuminance(0.1, 0.1, 0.1)
                }
                val ratio = WcagContrast.contrastRatio(glyphLuminance, fill.luminance)
                assertTrue(ratio >= WcagContrast.AA_FLOOR, "$name glyph vs fill is $ratio")
            }
        }
    }

    @Test
    fun categorySentinelsResolveToTheirFixedSwatches() {
        assertEquals(RgbColor.fromArgb(0xFF336688.toInt()), NeutralAvatarPalette.categoryColor(AvatarCategory.CHANNEL))
        assertEquals(RgbColor.fromArgb(0xFF00AAFF.toInt()), NeutralAvatarPalette.categoryColor(AvatarCategory.REPEATER))
        assertEquals(RgbColor.fromArgb(0xFFFF8800.toInt()), NeutralAvatarPalette.categoryColor(AvatarCategory.ROOM))
    }

    @Test
    fun categoryHuesAreDistinctAndOnAnchor() {
        val hues = AvatarCategory.anchorPriority.map { NeutralAvatarPalette.categoryHue(it) }
        assertEquals(hues.size, hues.toSet().size, "category hues collide: $hues")
        val anchors = gamut.sortedAnchors.toSet()
        assertTrue(hues.all { it in anchors }, "a category hue left the palette: $hues")
        assertEquals(listOf(180.0, 18.0, 215.0), hues, "category hue assignment drifted from Swift")
    }

    @Test
    fun gamutDerivedCategoryColorsClearAaAgainstTheCanvas() {
        for (dark in listOf(false, true)) {
            val canvas = if (dark) {
                WcagContrast.relativeLuminance(0.0, 0.0, 0.0)
            } else {
                WcagContrast.relativeLuminance(1.0, 1.0, 1.0)
            }
            for (category in AvatarCategory.entries) {
                val color = NeutralAvatarPalette.categoryGamutColor(category, dark)
                val ratio = WcagContrast.contrastRatio(color.luminance, canvas)
                assertTrue(ratio >= WcagContrast.AA_FLOOR, "$category vs canvas $canvas is $ratio")
            }
        }
    }

    @Test
    fun fnv1aMatchesSwift() {
        assertEquals(1313091536507228519uL, IdentityGamut.fnv1a("Alice"))
    }

    @Test
    fun identityGoldenVectorsMatchSwiftOutput() {
        // Captured from the pure-Double Swift IdentityGamut with the System gamut and the neutral
        // palette's exact surface luminances (canvas + secondarySystemBackground bubble).
        val light = mapOf(
            "Alice" to 0xFF6F3C47,
            "Bob" to 0xFF694230,
            "obB" to 0xFF7B3832,
            "灯火" to 0xFF82582E,
            "Søren" to 0xFF9537A9,
            "Sender 0" to 0xFF7B4641,
            "Sender 42" to 0xFF864780,
            "#general" to 0xFF775F37,
        )
        val dark = mapOf(
            "Alice" to 0xFFCE6F83,
            "Bob" to 0xFFC57C59,
            "obB" to 0xFFDE675B,
            "灯火" to 0xFFEBA053,
            "Søren" to 0xFFDC52FA,
            "Sender 0" to 0xFFDB7D74,
            "Sender 42" to 0xFFE97CDE,
            "#general" to 0xFFEDBE6D,
        )
        for ((name, argb) in light) {
            assertEquals(argb.toInt(), NeutralAvatarPalette.identityColor(name, dark = false).argb, "light $name")
        }
        for ((name, argb) in dark) {
            assertEquals(argb.toInt(), NeutralAvatarPalette.identityColor(name, dark = true).argb, "dark $name")
        }
        // Increased-contrast raises the floor and darkens the light-mode fill.
        val hcAlice = NeutralAvatarPalette.identityColor("Alice", dark = false, highContrast = true)
        assertEquals(0xFF512C34.toInt(), hcAlice.argb)
    }

    @Test
    fun categoryGoldenVectorsMatchSwiftOutput() {
        // Gamut-derived category path (atHue = distinct anchor, atVariety = darkest, canvas only).
        val light = mapOf(
            AvatarCategory.CHANNEL to 0xFF1F5555,
            AvatarCategory.REPEATER to 0xFF71412D,
            AvatarCategory.ROOM to 0xFF2F4E7A,
        )
        val dark = mapOf(
            AvatarCategory.CHANNEL to 0xFF318585,
            AvatarCategory.REPEATER to 0xFFB16646,
            AvatarCategory.ROOM to 0xFF4A7BBF,
        )
        for ((category, argb) in light) {
            val color = NeutralAvatarPalette.categoryGamutColor(category, dark = false)
            assertEquals(argb.toInt(), color.argb, "light $category")
        }
        for ((category, argb) in dark) {
            val color = NeutralAvatarPalette.categoryGamutColor(category, dark = true)
            assertEquals(argb.toInt(), color.argb, "dark $category")
        }
    }

    @Test
    fun argbRoundTripsThroughFromArgb() {
        for (argb in listOf(0xFF336688.toInt(), 0xFF00AAFF.toInt(), 0xFFFF8800.toInt())) {
            assertEquals(argb, RgbColor.fromArgb(argb).argb)
        }
    }

    private companion object {
        const val NAME_SPREAD = 300
        const val MIN_DISTINCT_HUES = 100
    }
}
