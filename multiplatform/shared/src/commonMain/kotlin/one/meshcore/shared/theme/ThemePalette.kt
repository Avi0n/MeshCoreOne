package one.meshcore.shared.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import one.meshcore.shared.avatar.CategoryAvatarColors
import one.meshcore.shared.avatar.IdentityGamut
import one.meshcore.shared.avatar.RgbColor
import one.meshcore.shared.avatar.WcagContrast

/**
 * Light/dark surface + accent roles for a named theme, sourced from the iOS asset catalog.
 * [surfaceContainer] maps to the iOS card tier; null means canvas-only (Ember).
 */
data class ThemePalette(
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val surfaceContainer: Long? = null,
)

/**
 * Named cosmetic theme: Material roles, identity gamut for avatars, and free/paid gate.
 * [forceDark] forces dark appearance regardless of the scheme preference (Ember).
 */
data class AppTheme(
    val id: String,
    /** Accent used on Appearance swatches (typically the light primary). */
    val accentArgb: Long,
    val forceDark: Boolean? = null,
    val isFree: Boolean = false,
    /** Null for default/system theme — uses platform Material or dynamic color. */
    val light: ThemePalette? = null,
    val dark: ThemePalette? = null,
    val identityGamut: IdentityGamut,
    /** When non-null, pins category avatars; otherwise hues are solved from [identityGamut]. */
    val categoryAvatarOverride: CategoryAvatarColors? = null,
    /** Optional curated category hues (degrees) for gamut themes. */
    val categoryHues: CategoryHues? = null,
    /** Incoming `#hashtag` fill from the iOS Hashtag colorset (light / dark / high contrast). */
    val hashtag: HashtagColors,
) {
    /** True when this theme pins category avatars to fixed colors (System) rather than the gamut. */
    val usesCategoryAvatarOverride: Boolean get() = categoryAvatarOverride != null

    /**
     * Whether the glyph on [fill] should be white. System override always uses white; gamut fills
     * pick white or near-black from luminance so the icon stays readable. Mirrors iOS `avatarGlyphColor`.
     */
    fun avatarGlyphIsWhite(fill: RgbColor): Boolean =
        usesCategoryAvatarOverride || IdentityGamut.glyphIsWhite(fill.luminance)

    /** Resolved hashtag ARGB for the current appearance, including Ember's forced dark. */
    fun hashtagArgb(useDark: Boolean, highContrast: Boolean = false): Long {
        val isDark = forceDark ?: useDark
        return when {
            highContrast && isDark -> hashtag.highContrastDark
            highContrast -> hashtag.highContrastLight
            isDark -> hashtag.dark
            else -> hashtag.light
        }
    }
    fun materialColorScheme(useDark: Boolean): ColorScheme {
        val isDark = forceDark ?: useDark
        val palette = if (isDark) dark else light
        if (palette == null) {
            return if (isDark) darkColorScheme() else lightColorScheme()
        }
        return buildColorScheme(palette, isDark)
    }

    /** Surface luminances for identity avatar WCAG solving (canvas + card/bubble tier). */
    fun identitySurfaceLuminances(useDark: Boolean): List<Double> {
        val isDark = forceDark ?: useDark
        val palette = if (isDark) dark else light
        if (palette == null) {
            return listOf(
                if (isDark) 0.0 else 1.0,
                if (isDark) {
                    WcagContrast.relativeLuminance(
                        SYSTEM_DARK_CARD_RED / SRGB_CHANNEL_MAX,
                        SYSTEM_DARK_CARD_GREEN / SRGB_CHANNEL_MAX,
                        SYSTEM_DARK_CARD_BLUE / SRGB_CHANNEL_MAX,
                    )
                } else {
                    WcagContrast.relativeLuminance(
                        SYSTEM_LIGHT_CARD_RED / SRGB_CHANNEL_MAX,
                        SYSTEM_LIGHT_CARD_GREEN / SRGB_CHANNEL_MAX,
                        SYSTEM_LIGHT_CARD_BLUE / SRGB_CHANNEL_MAX,
                    )
                },
            )
        }
        val canvas = Color(palette.background)
        val cardArgb = palette.surfaceContainer ?: palette.background
        val card = Color(cardArgb)
        return listOf(colorLuminance(canvas), colorLuminance(card))
    }

    fun categoryColor(
        category: one.meshcore.shared.avatar.AvatarCategory,
        useDark: Boolean,
        highContrast: Boolean = false,
    ): RgbColor {
        categoryAvatarOverride?.let { return it.colorFor(category) }
        val hues = categoryHues
        val hue = when {
            hues != null -> when (category) {
                one.meshcore.shared.avatar.AvatarCategory.CHANNEL -> hues.channel
                one.meshcore.shared.avatar.AvatarCategory.REPEATER -> hues.repeater
                one.meshcore.shared.avatar.AvatarCategory.ROOM -> hues.room
            }
            else -> {
                val distinct = identityGamut.distinctAnchorHues(
                    one.meshcore.shared.avatar.AvatarCategory.anchorPriority.map { it.gamutSeed },
                )
                distinct[one.meshcore.shared.avatar.AvatarCategory.anchorPriority.indexOf(category)]
            }
        }
        val isDark = forceDark ?: useDark
        return identityGamut.colorForName(
            name = category.gamutSeed,
            backgroundLuminances = listOf(identitySurfaceLuminances(isDark).first()),
            highContrast = highContrast,
            atHue = hue,
            atVariety = IdentityGamut.CATEGORY_DARKEST_VARIETY,
        )
    }

    companion object {
        const val DEFAULT_ID = "default"
    }
}

/** Curated channel / repeater / room avatar hues for a gamut theme. */
data class CategoryHues(val channel: Double, val repeater: Double, val room: Double)

/**
 * Incoming hashtag tokens from the matching iOS Hashtag colorset.
 * [highContrastDark] falls back to [dark] when the asset has no dark+high-contrast pair.
 */
data class HashtagColors(
    val light: Long,
    val dark: Long,
    val highContrastLight: Long,
    val highContrastDark: Long = dark,
)

internal fun buildColorScheme(palette: ThemePalette, dark: Boolean): ColorScheme {
    val tones = paletteTones(palette, dark)
    val ink = paletteInk(tones)
    return if (dark) darkScheme(tones, ink) else lightScheme(tones, ink)
}

private data class PaletteTones(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceBright: Color,
    val surfaceDim: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val error: Color,
    val errorContainer: Color,
    val inversePrimary: Color,
    val scrim: Color,
    val surfaceTint: Color,
)

private data class PaletteInk(
    val onPrimary: Color,
    val onSecondary: Color,
    val onTertiary: Color,
    val onBackground: Color,
    val onSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val onError: Color,
    val onErrorContainer: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
)

private fun paletteTones(palette: ThemePalette, dark: Boolean): PaletteTones {
    val primary = Color(palette.primary)
    val secondary = Color(palette.secondary)
    val background = Color(palette.background)
    val surfaceContainer = palette.surfaceContainer?.let { Color(it) }
        ?: blendToward(background, primary, SURFACE_BLEND)
    val surface = background
    val surfaceBright = if (dark) {
        blendToward(surfaceContainer, Color.White, DARK_SURFACE_BRIGHT_BLEND)
    } else {
        blendToward(surfaceContainer, Color.White, LIGHT_SURFACE_BRIGHT_BLEND)
    }
    val surfaceDim = if (dark) {
        blendToward(background, Color.Black, DARK_SURFACE_DIM_BLEND)
    } else {
        blendToward(background, Color.Black, LIGHT_SURFACE_DIM_BLEND)
    }
    val surfaceContainerLow = blendToward(background, surfaceContainer, SURFACE_CONTAINER_LOW_BLEND)
    val surfaceContainerHigh = blendToward(surfaceContainer, primary, SURFACE_CONTAINER_HIGH_BLEND)
    val surfaceContainerHighest = blendToward(surfaceContainer, primary, SURFACE_CONTAINER_HIGHEST_BLEND)
    val tertiary = blendToward(primary, secondary, TERTIARY_BLEND)
    val error = if (dark) Color(ERROR_DARK_ARGB) else Color(ERROR_LIGHT_ARGB)
    val errorContainer = if (dark) Color(ERROR_CONTAINER_DARK_ARGB) else Color(ERROR_CONTAINER_LIGHT_ARGB)
    val inversePrimary = blendToward(primary, if (dark) Color.White else Color.Black, INVERSE_PRIMARY_BLEND)
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    return PaletteTones(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        background = background,
        surface = surface,
        surfaceContainer = surfaceContainer,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        error = error,
        errorContainer = errorContainer,
        inversePrimary = inversePrimary,
        scrim = scrim,
        surfaceTint = primary,
    )
}

private fun paletteInk(tones: PaletteTones): PaletteInk {
    val onBackground = contrastingContent(tones.background)
    val onSurface = contrastingContent(tones.surface)
    val onPrimary = contrastingContent(tones.primary)
    val onSecondary = contrastingContent(tones.secondary)
    val onTertiary = contrastingContent(tones.tertiary)
    val onError = contrastingContent(tones.error)
    val onErrorContainer = contrastingContent(tones.errorContainer)
    return PaletteInk(
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onTertiary = onTertiary,
        onBackground = onBackground,
        onSurface = onSurface,
        outline = onSurface.copy(alpha = OUTLINE_ALPHA),
        outlineVariant = onSurface.copy(alpha = OUTLINE_VARIANT_ALPHA),
        onError = onError,
        onErrorContainer = onErrorContainer,
        inverseSurface = onSurface,
        inverseOnSurface = tones.surface,
    )
}

private fun darkScheme(tones: PaletteTones, ink: PaletteInk): ColorScheme = darkColorScheme(
    primary = tones.primary,
    onPrimary = ink.onPrimary,
    primaryContainer = blendToward(tones.primary, tones.background, DARK_ROLE_CONTAINER_BLEND),
    onPrimaryContainer = contrastingContent(blendToward(tones.primary, tones.background, DARK_ROLE_CONTAINER_BLEND)),
    secondary = tones.secondary,
    onSecondary = ink.onSecondary,
    secondaryContainer = blendToward(tones.secondary, tones.background, DARK_ROLE_CONTAINER_BLEND),
    onSecondaryContainer = contrastingContent(
        blendToward(tones.secondary, tones.background, DARK_ROLE_CONTAINER_BLEND),
    ),
    tertiary = tones.tertiary,
    onTertiary = ink.onTertiary,
    tertiaryContainer = blendToward(tones.tertiary, tones.background, DARK_ROLE_CONTAINER_BLEND),
    onTertiaryContainer = contrastingContent(blendToward(tones.tertiary, tones.background, DARK_ROLE_CONTAINER_BLEND)),
    background = tones.background,
    onBackground = ink.onBackground,
    surface = tones.surface,
    onSurface = ink.onSurface,
    surfaceVariant = tones.surfaceContainer,
    onSurfaceVariant = ink.onSurface.copy(alpha = DARK_ON_SURFACE_VARIANT_ALPHA),
    surfaceTint = tones.surfaceTint,
    inverseSurface = ink.inverseSurface,
    inverseOnSurface = ink.inverseOnSurface,
    inversePrimary = tones.inversePrimary,
    error = tones.error,
    onError = ink.onError,
    errorContainer = tones.errorContainer,
    onErrorContainer = ink.onErrorContainer,
    outline = ink.outline,
    outlineVariant = ink.outlineVariant,
    scrim = tones.scrim,
    surfaceBright = tones.surfaceBright,
    surfaceDim = tones.surfaceDim,
    surfaceContainer = tones.surfaceContainer,
    surfaceContainerLow = tones.surfaceContainerLow,
    surfaceContainerHigh = tones.surfaceContainerHigh,
    surfaceContainerHighest = tones.surfaceContainerHighest,
    surfaceContainerLowest = blendToward(tones.background, Color.Black, DARK_SURFACE_LOWEST_BLEND),
)

private fun lightScheme(tones: PaletteTones, ink: PaletteInk): ColorScheme = lightColorScheme(
    primary = tones.primary,
    onPrimary = ink.onPrimary,
    primaryContainer = blendToward(tones.primary, Color.White, LIGHT_ROLE_CONTAINER_BLEND),
    onPrimaryContainer = contrastingContent(blendToward(tones.primary, Color.White, LIGHT_ROLE_CONTAINER_BLEND)),
    secondary = tones.secondary,
    onSecondary = ink.onSecondary,
    secondaryContainer = blendToward(tones.secondary, Color.White, LIGHT_ROLE_CONTAINER_BLEND),
    onSecondaryContainer = contrastingContent(blendToward(tones.secondary, Color.White, LIGHT_ROLE_CONTAINER_BLEND)),
    tertiary = tones.tertiary,
    onTertiary = ink.onTertiary,
    tertiaryContainer = blendToward(tones.tertiary, Color.White, LIGHT_ROLE_CONTAINER_BLEND),
    onTertiaryContainer = contrastingContent(blendToward(tones.tertiary, Color.White, LIGHT_ROLE_CONTAINER_BLEND)),
    background = tones.background,
    onBackground = ink.onBackground,
    surface = tones.surface,
    onSurface = ink.onSurface,
    surfaceVariant = tones.surfaceContainer,
    onSurfaceVariant = ink.onSurface.copy(alpha = LIGHT_ON_SURFACE_VARIANT_ALPHA),
    surfaceTint = tones.surfaceTint,
    inverseSurface = ink.inverseSurface,
    inverseOnSurface = ink.inverseOnSurface,
    inversePrimary = tones.inversePrimary,
    error = tones.error,
    onError = ink.onError,
    errorContainer = tones.errorContainer,
    onErrorContainer = ink.onErrorContainer,
    outline = ink.outline,
    outlineVariant = ink.outlineVariant,
    scrim = tones.scrim,
    surfaceBright = tones.surfaceBright,
    surfaceDim = tones.surfaceDim,
    surfaceContainer = tones.surfaceContainer,
    surfaceContainerLow = tones.surfaceContainerLow,
    surfaceContainerHigh = tones.surfaceContainerHigh,
    surfaceContainerHighest = tones.surfaceContainerHighest,
    surfaceContainerLowest = Color.White,
)

private fun contrastingContent(background: Color): Color = if (background.luminance() > CONTENT_LUMINANCE_MIDPOINT) {
    Color(CONTENT_ON_LIGHT_ARGB)
} else {
    Color(CONTENT_ON_DARK_ARGB)
}

private fun blendToward(from: Color, toward: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (toward.red - from.red) * t,
        green = from.green + (toward.green - from.green) * t,
        blue = from.blue + (toward.blue - from.blue) * t,
        alpha = from.alpha + (toward.alpha - from.alpha) * t,
    )
}

private fun colorLuminance(color: Color): Double =
    WcagContrast.relativeLuminance(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

private const val SURFACE_BLEND = 0.08f
private const val DARK_SURFACE_BRIGHT_BLEND = 0.08f
private const val LIGHT_SURFACE_BRIGHT_BLEND = 0.35f
private const val DARK_SURFACE_DIM_BLEND = 0.15f
private const val LIGHT_SURFACE_DIM_BLEND = 0.04f
private const val SURFACE_CONTAINER_LOW_BLEND = 0.35f
private const val SURFACE_CONTAINER_HIGH_BLEND = 0.06f
private const val SURFACE_CONTAINER_HIGHEST_BLEND = 0.12f
private const val TERTIARY_BLEND = 0.45f
private const val INVERSE_PRIMARY_BLEND = 0.25f
private const val DARK_ROLE_CONTAINER_BLEND = 0.55f
private const val LIGHT_ROLE_CONTAINER_BLEND = 0.75f
private const val DARK_SURFACE_LOWEST_BLEND = 0.2f
private const val SCRIM_ALPHA = 0.32f
private const val OUTLINE_ALPHA = 0.38f
private const val OUTLINE_VARIANT_ALPHA = 0.22f
private const val DARK_ON_SURFACE_VARIANT_ALPHA = 0.80f
private const val LIGHT_ON_SURFACE_VARIANT_ALPHA = 0.75f
private const val CONTENT_LUMINANCE_MIDPOINT = 0.5f
private const val ERROR_DARK_ARGB = 0xFFF2B8B5
private const val ERROR_LIGHT_ARGB = 0xFFB3261E
private const val ERROR_CONTAINER_DARK_ARGB = 0xFF8C1D18
private const val ERROR_CONTAINER_LIGHT_ARGB = 0xFFF9DEDC
private const val CONTENT_ON_LIGHT_ARGB = 0xFF1A1C1E
private const val CONTENT_ON_DARK_ARGB = 0xFFE2E2E6
private const val SYSTEM_DARK_CARD_RED = 28.0
private const val SYSTEM_DARK_CARD_GREEN = 28.0
private const val SYSTEM_DARK_CARD_BLUE = 30.0
private const val SYSTEM_LIGHT_CARD_RED = 242.0
private const val SYSTEM_LIGHT_CARD_GREEN = 242.0
private const val SYSTEM_LIGHT_CARD_BLUE = 247.0
private const val SRGB_CHANNEL_MAX = 255.0
