package one.meshcore.shared.avatar

/**
 * System-theme reference palette for avatar and formatted-text tests. It keeps the iOS System identity
 * gamut, fixed category swatches, and captured surface luminances in one fixture so theme-specific tests
 * can prove they differ from the baseline while contrast tests share the same inputs.
 */
object NeutralAvatarPalette {
    /** The System theme's identity gamut (`Theme.default.identityGamut`). */
    val gamut = IdentityGamut(
        hueAnchors = listOf(18.0, 25.0, 44.0, 77.0, 120.0, 180.0, 215.0, 255.0, 307.0, 343.0),
        saturation = 0.45..0.70,
    )

    /** Fixed category swatches from the System theme override. */
    val categoryColors = CategoryAvatarColors.SYSTEM_DEFAULT

    /**
     * Identity color for a contact avatar / sender name with the given [name]. Solved to clear AA
     * against both surfaces the color renders on (list canvas and incoming bubble) so one identity keeps
     * a single legible color across avatar and sender name.
     */
    fun identityColor(name: String, dark: Boolean, highContrast: Boolean = false): RgbColor =
        gamut.colorForName(name, identitySurfaceLuminances(dark), highContrast)

    /** The fixed swatch for a channel / repeater / room avatar in the neutral palette. */
    fun categoryColor(category: AvatarCategory): RgbColor = categoryColors.colorFor(category)

    /**
     * The gamut-derived category color a theme without a fixed override would produce: resolved at the
     * category's distinct anchor hue, against the list canvas only, at the darkest legible brightness.
     * The System baseline pins fixed swatches; this helper exercises the fallback contract for themes
     * without category overrides.
     */
    fun categoryGamutColor(category: AvatarCategory, dark: Boolean, highContrast: Boolean = false): RgbColor {
        val hue = categoryHue(category)
        return gamut.colorForName(
            name = category.gamutSeed,
            backgroundLuminances = listOf(canvasLuminance(dark)),
            highContrast = highContrast,
            atHue = hue,
            atVariety = IdentityGamut.CATEGORY_DARKEST_VARIETY,
        )
    }

    /** Distinct on-anchor hue for [category], assigned by anchor priority over the sentinel seeds. */
    fun categoryHue(category: AvatarCategory): Double {
        val hues = gamut.distinctAnchorHues(AvatarCategory.anchorPriority.map { it.gamutSeed })
        return hues[AvatarCategory.anchorPriority.indexOf(category)]
    }

    /** Whether the glyph on the given fill should be white rather than near-black. */
    fun glyphIsWhite(fill: RgbColor): Boolean = IdentityGamut.glyphIsWhite(fill.luminance)

    // Surface luminances derived from the iOS system colors via the WCAG formula (computed here rather
    // than hardcoded, so they cannot drift from the golden-capture inputs). Canvas is systemBackground;
    // the second identity surface is the incoming-bubble tier (secondarySystemBackground).
    private const val LIGHT_BUBBLE_CHANNEL = 242.0 / 255.0
    private const val LIGHT_BUBBLE_BLUE = 247.0 / 255.0
    private const val DARK_BUBBLE_CHANNEL = 28.0 / 255.0
    private const val DARK_BUBBLE_BLUE = 30.0 / 255.0

    private fun canvasLuminance(dark: Boolean): Double =
        if (dark) WcagContrast.relativeLuminance(0.0, 0.0, 0.0) else WcagContrast.relativeLuminance(1.0, 1.0, 1.0)

    private fun bubbleLuminance(dark: Boolean): Double = if (dark) {
        WcagContrast.relativeLuminance(DARK_BUBBLE_CHANNEL, DARK_BUBBLE_CHANNEL, DARK_BUBBLE_BLUE)
    } else {
        WcagContrast.relativeLuminance(LIGHT_BUBBLE_CHANNEL, LIGHT_BUBBLE_CHANNEL, LIGHT_BUBBLE_BLUE)
    }

    private fun identitySurfaceLuminances(dark: Boolean): List<Double> =
        listOf(canvasLuminance(dark), bubbleLuminance(dark))
}
