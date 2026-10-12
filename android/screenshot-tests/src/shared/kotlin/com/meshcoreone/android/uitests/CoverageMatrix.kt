// AndroidOnly: WP-502 Deterministic layered pairwise inputs; not an image oracle or test result.
// ReferenceInput: MC1Tests/ThemeRegistryTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.ThemeId

enum class ConversationState { OFFLINE, PENDING, FAILED, DELIVERED }

data class ScreenCase(
    val id: String,
    val theme: ThemeId = ThemeId.DEFAULT,
    val preference: AppColorSchemePreference = AppColorSchemePreference.LIGHT,
    val expectedScheme: ColorScheme = ColorScheme.LIGHT,
    val locale: String = "en",
    val widthDp: Int = 360,
    val heightDp: Int = 900,
    val fontScale: Float = 1f,
    val rtl: Boolean = false,
    val systemDark: Boolean = false,
    val state: ConversationState = ConversationState.OFFLINE,
)

object CoverageMatrix {
    val locales = listOf("en", "de", "es", "fr", "it", "ko", "nl", "pl", "pt-PT", "ru", "uk", "zh-Hans")
    val widths = listOf(360, 744, 834)
    val fonts = listOf(1f, 2f)

    // This oracle is independent of the candidate ThemeRegistry's preferredColorScheme property.
    val themeFrames: List<ScreenCase> = ThemeId.entries.flatMap { theme ->
        val schemes = if (theme == ThemeId.EMBER) listOf(ColorScheme.DARK) else ColorScheme.entries
        schemes.map { scheme ->
            ScreenCase(
                id = "theme-${theme.rawValue}-${scheme.name.lowercase()}",
                theme = theme,
                preference = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT,
                expectedScheme = scheme,
            )
        }
    }

    val systemFrames = ThemeId.entries.flatMap { theme ->
        listOf(false, true).map { dark ->
            ScreenCase(
                id = "system-${theme.rawValue}-${if (dark) "dark" else "light"}",
                theme = theme, preference = AppColorSchemePreference.SYSTEM, systemDark = dark,
                expectedScheme = if (theme == ThemeId.EMBER || dark) ColorScheme.DARK else ColorScheme.LIGHT,
            )
        }
    }

    val themeWindowFontCases = themeFrames.flatMapIndexed { frameIndex, frame ->
        widths.mapIndexed { widthIndex, width ->
            val font = fonts[(frameIndex + widthIndex) % fonts.size]
            frame.copy(
                id = "${frame.id}-w$width-f${font.toInt()}",
                widthDp = width, fontScale = font, rtl = (frameIndex + widthIndex) % 2 != 0,
            )
        }
    }

    fun assertThemeWindowFontCoverage() {
        check(themeWindowFontCases.size == 57)
        for (frame in themeFrames) {
            val cases = themeWindowFontCases.filter { it.theme == frame.theme && it.expectedScheme == frame.expectedScheme }
            check(cases.map { it.widthDp }.toSet() == widths.toSet())
            check(cases.map { it.fontScale }.toSet() == fonts.toSet())
            check(cases.map { it.rtl }.toSet() == setOf(false, true))
        }
    }

    val pairwiseStates: List<ScreenCase> = locales.flatMapIndexed { localeIndex, locale ->
        ConversationState.entries.mapIndexed { stateIndex, state ->
            ScreenCase(
                id = "state-$locale-${state.name.lowercase()}",
                locale = locale,
                widthDp = widths[(localeIndex + stateIndex) % widths.size],
                fontScale = fonts[(localeIndex + stateIndex / 2) % fonts.size],
                rtl = (localeIndex + stateIndex) % 2 != 0,
                state = state,
            )
        }
    }

    fun pairwiseFactors(case: ScreenCase): List<String> = listOf(
        case.locale, case.state.name, case.widthDp.toString(), case.fontScale.toString(), case.rtl.toString(),
    )

    fun assertCompletePairCoverage() {
        val domains = listOf(
            locales, ConversationState.entries.map { it.name }, widths.map { it.toString() },
            fonts.map { it.toString() }, listOf("false", "true"),
        )
        check(pairwiseStates.size == 48)
        check(pairwiseStates.map { it.id }.toSet().size == pairwiseStates.size)
        for (first in domains.indices) for (second in first + 1 until domains.size) {
            val required = domains[first].flatMap { a -> domains[second].map { b -> a to b } }.toSet()
            val observed = pairwiseStates.map { pairwiseFactors(it).let { values -> values[first] to values[second] } }.toSet()
            check(observed == required) { "Missing pairs for factors $first/$second: ${required - observed}" }
        }
    }
}
