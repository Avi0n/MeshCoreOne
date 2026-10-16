package one.meshcore.shared.onboarding

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.settings.RadioPresets
import one.meshcore.shared.settings.ui.isRetryableSettingsError

/**
 * The presets the onboarding picker offers. A picked region uses that country's catalog (iOS
 * `RadioPresets.presets(for:)`); locale catalog is only the fallback when the region list is empty.
 * County-scoped presets stay gated to a matching [RegionSelection]. Sorted by name, matching iOS
 * `PresetStepView.visiblePresets` — the recommendation is not pinned to the front.
 */
fun onboardingSelectablePresets(region: RegionSelection?): List<RadioPreset> {
    val base = if (region != null) {
        val regional = RadioPresets.presets(region)
        if (regional.isNotEmpty()) regional else RadioPresets.presetsForLocale(region.countryCode)
    } else {
        RadioPresets.presetsForLocale(null)
    }
    return base.filter { RadioPresets.isSelectable(it, region) }.sortedBy { it.name }
}

private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\(([^)]*)\)""")

/**
 * Replaces markdown `[text](url)` spans with their URL, so a string authored for the iOS markdown
 * renderer reads cleanly as plain text.
 */
fun flattenMarkdownLinks(text: String): String = MARKDOWN_LINK.replace(text) { match -> match.groupValues[2] }

/** Display text plus the first markdown URL's range in that display, for a tappable Compose span. */
data class MarkdownLinkDisplay(val text: String, val url: String? = null, val urlStart: Int = 0, val urlEnd: Int = 0)

fun markdownLinkDisplay(source: String): MarkdownLinkDisplay {
    val text = flattenMarkdownLinks(source)
    val match = MARKDOWN_LINK.find(source) ?: return MarkdownLinkDisplay(text)
    val url = match.groupValues[2]
    val start = match.range.first
    return MarkdownLinkDisplay(text, url, start, start + url.length)
}

/** Result of applying the selected onboarding preset over the live session. */
internal sealed interface OnboardingPresetApply {
    data object Applied : OnboardingPresetApply

    data class Retryable(val message: String) : OnboardingPresetApply

    data class Failed(val message: String) : OnboardingPresetApply
}

/**
 * Runs [apply], then [onApplied] (retry reset, success haptic, onboarding complete).
 * A retryable settings fault returns [OnboardingPresetApply.Retryable]; any other [Exception]
 * returns [OnboardingPresetApply.Failed]. Cancellation leaves both undone.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun applyOnboardingPreset(
    apply: suspend () -> Unit,
    onApplied: suspend () -> Unit,
    errorText: (Throwable) -> String,
): OnboardingPresetApply = try {
    apply()
    onApplied()
    OnboardingPresetApply.Applied
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val message = errorText(error)
    if (error.isRetryableSettingsError()) {
        OnboardingPresetApply.Retryable(message)
    } else {
        OnboardingPresetApply.Failed(message)
    }
}
