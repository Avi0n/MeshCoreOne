package one.meshcore.shared.theme

import one.meshcore.shared.avatar.CategoryAvatarColors
import one.meshcore.shared.avatar.IdentityGamut

/**
 * Catalog of all 9 named themes plus default. Palettes match iOS asset catalog light/dark roles;
 * identity gamuts match `Theme.swift` so avatar colors stay cross-platform stable.
 */
object ThemeRegistry {
    val all: List<AppTheme> = listOf(
        AppTheme(
            id = AppTheme.DEFAULT_ID,
            accentArgb = 0xFF6750A4,
            isFree = true,
            identityGamut = IdentityGamut(
                hueAnchors = listOf(18.0, 25.0, 44.0, 77.0, 120.0, 180.0, 215.0, 255.0, 307.0, 343.0),
                saturation = 0.45..0.70,
            ),
            categoryAvatarOverride = CategoryAvatarColors.SYSTEM_DEFAULT,
            hashtag = HashtagColors(
                light = 0xFF007A8F,
                dark = 0xFF5AD2EA,
                highContrastLight = 0xFF006478,
            ),
        ),
        AppTheme(
            id = "ember",
            accentArgb = 0xFFB81A1A,
            forceDark = true,
            dark = ThemePalette(
                primary = 0xFFB81A1A,
                secondary = 0xFFFFBF4C,
                background = 0xFF000000,
                surfaceContainer = null,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(0.0, 8.0, 12.0, 24.0, 18.0, 345.0),
                saturation = 0.50..0.82,
            ),
            categoryHues = CategoryHues(channel = 0.0, repeater = 24.0, room = 345.0),
            hashtag = HashtagColors(
                light = 0xFFFFBF4D,
                dark = 0xFFFFBF4D,
                highContrastLight = 0xFFFFD166,
                highContrastDark = 0xFFFFD166,
            ),
        ),
        AppTheme(
            id = "fern",
            accentArgb = 0xFF2E662E,
            light = ThemePalette(
                primary = 0xFF2E662E,
                secondary = 0xFF2E6B1F,
                background = 0xFFF1F4EE,
                surfaceContainer = 0xFFFCFDFC,
            ),
            dark = ThemePalette(
                primary = 0xFF8CCC80,
                secondary = 0xFF9ED973,
                background = 0xFF0F1410,
                surfaceContainer = 0xFF1F2922,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(72.0, 90.0, 108.0, 128.0, 150.0, 165.0),
                saturation = 0.35..0.65,
            ),
            categoryHues = CategoryHues(channel = 90.0, repeater = 128.0, room = 165.0),
            hashtag = HashtagColors(
                light = 0xFF2E6B1F,
                dark = 0xFF9ED973,
                highContrastLight = 0xFF245C17,
            ),
        ),
        AppTheme(
            id = "marine",
            accentArgb = 0xFF1A408C,
            light = ThemePalette(
                primary = 0xFF1A408C,
                secondary = 0xFF0F6B8F,
                background = 0xFFF0F4F8,
                surfaceContainer = 0xFFFCFCFD,
            ),
            dark = ThemePalette(
                primary = 0xFF456ECC,
                secondary = 0xFF8CD9FF,
                background = 0xFF0E1620,
                surfaceContainer = 0xFF1A2330,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(178.0, 188.0, 200.0, 215.0, 232.0, 245.0),
                saturation = 0.40..0.72,
            ),
            categoryHues = CategoryHues(channel = 215.0, repeater = 188.0, room = 245.0),
            hashtag = HashtagColors(
                light = 0xFF0F6B8F,
                dark = 0xFF8CD9FF,
                highContrastLight = 0xFF0A5C7D,
            ),
        ),
        AppTheme(
            id = "olive",
            accentArgb = 0xFF4C662E,
            light = ThemePalette(
                primary = 0xFF4C662E,
                secondary = 0xFF3D7024,
                background = 0xFFF4F3EC,
                surfaceContainer = 0xFFFDFDFB,
            ),
            dark = ThemePalette(
                primary = 0xFF8CA64C,
                secondary = 0xFF8CF28C,
                background = 0xFF13130C,
                surfaceContainer = 0xFF25241B,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(45.0, 55.0, 70.0, 85.0, 100.0, 112.0),
                saturation = 0.35..0.62,
            ),
            categoryHues = CategoryHues(channel = 85.0, repeater = 112.0, room = 70.0),
            hashtag = HashtagColors(
                light = 0xFF3D7024,
                dark = 0xFF8CF28C,
                highContrastLight = 0xFF30611A,
            ),
        ),
        AppTheme(
            id = "lavender",
            accentArgb = 0xFF6B47AD,
            light = ThemePalette(
                primary = 0xFF6B47AD,
                secondary = 0xFF85529E,
                background = 0xFFF4F1F8,
                surfaceContainer = 0xFFFDFCFD,
            ),
            dark = ThemePalette(
                primary = 0xFFC7A6F2,
                secondary = 0xFFE0B2F2,
                background = 0xFF150F1C,
                surfaceContainer = 0xFF271D33,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(222.0, 238.0, 248.0, 265.0, 285.0, 302.0),
                saturation = 0.35..0.62,
            ),
            categoryHues = CategoryHues(channel = 265.0, repeater = 222.0, room = 302.0),
            hashtag = HashtagColors(
                light = 0xFF85529E,
                dark = 0xFFE0B3F2,
                highContrastLight = 0xFF73458C,
            ),
        ),
        AppTheme(
            id = "sakura",
            accentArgb = 0xFF8C3373,
            light = ThemePalette(
                primary = 0xFF8C3373,
                secondary = 0xFF9E5C2E,
                background = 0xFFF8F2F4,
                surfaceContainer = 0xFFFDFCFD,
            ),
            dark = ThemePalette(
                primary = 0xFFF2B2D1,
                secondary = 0xFFFFCC8C,
                background = 0xFF1A1014,
                surfaceContainer = 0xFF2C1D24,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(290.0, 305.0, 320.0, 335.0, 350.0, 8.0),
                saturation = 0.40..0.70,
            ),
            categoryHues = CategoryHues(channel = 320.0, repeater = 290.0, room = 350.0),
            hashtag = HashtagColors(
                light = 0xFF9E5C2E,
                dark = 0xFFFFCC8C,
                highContrastLight = 0xFF8A4D24,
            ),
        ),
        AppTheme(
            id = "solarized",
            accentArgb = 0xFF1E77B5,
            light = ThemePalette(
                primary = 0xFF1E77B5,
                secondary = 0xFF207B74,
                background = 0xFFEEE8D5,
                surfaceContainer = 0xFFFDF6E3,
            ),
            dark = ThemePalette(
                primary = 0xFF1E77B5,
                secondary = 0xFF31BCB2,
                background = 0xFF002B36,
                surfaceContainer = 0xFF073642,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(1.0, 18.0, 45.0, 68.0, 175.0, 205.0, 237.0, 331.0),
                saturation = 0.50..0.80,
            ),
            categoryHues = CategoryHues(channel = 205.0, repeater = 175.0, room = 331.0),
            hashtag = HashtagColors(
                light = 0xFF207B74,
                dark = 0xFF31BCB2,
                highContrastLight = 0xFF1E8077,
            ),
        ),
        AppTheme(
            id = "nord",
            accentArgb = 0xFF4C6F9E,
            light = ThemePalette(
                primary = 0xFF4C6F9E,
                secondary = 0xFF567983,
                background = 0xFFECEFF4,
                surfaceContainer = 0xFFFFFFFF,
            ),
            dark = ThemePalette(
                primary = 0xFF88C0D0,
                secondary = 0xFFE2B3DA,
                background = 0xFF2E3440,
                surfaceContainer = 0xFF3B4252,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(14.0, 40.0, 92.0, 178.0, 193.0, 210.0, 213.0, 240.0, 280.0, 311.0, 354.0),
                saturation = 0.25..0.52,
            ),
            categoryHues = CategoryHues(channel = 210.0, repeater = 193.0, room = 280.0),
            hashtag = HashtagColors(
                light = 0xFF567983,
                dark = 0xFFE2B3DA,
                highContrastLight = 0xFF517A86,
            ),
        ),
        AppTheme(
            id = "catppuccin",
            accentArgb = 0xFF8839EF,
            light = ThemePalette(
                primary = 0xFF8839EF,
                secondary = 0xFF174FBE,
                background = 0xFFEFF1F5,
                surfaceContainer = 0xFFCCD0DA,
            ),
            dark = ThemePalette(
                primary = 0xFFCBA6F7,
                secondary = 0xFF89B4FA,
                background = 0xFF1E1E2E,
                surfaceContainer = 0xFF313244,
            ),
            identityGamut = IdentityGamut(
                hueAnchors = listOf(
                    0.0, 10.0, 23.0, 41.0, 115.0, 170.0, 189.0, 199.0, 217.0, 232.0, 267.0, 316.0, 343.0, 351.0,
                ),
                saturation = 0.40..0.70,
            ),
            categoryHues = CategoryHues(channel = 267.0, repeater = 217.0, room = 316.0),
            hashtag = HashtagColors(
                light = 0xFF174FBE,
                dark = 0xFF89B4FA,
                highContrastLight = 0xFF0E43B1,
                highContrastDark = 0xFFA6C8FF,
            ),
        ),
    )

    fun byId(id: String): AppTheme = all.firstOrNull { it.id == id } ?: all.first { it.id == AppTheme.DEFAULT_ID }
}
