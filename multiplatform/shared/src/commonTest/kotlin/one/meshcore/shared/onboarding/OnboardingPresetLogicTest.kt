package one.meshcore.shared.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.settings.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The presets the onboarding picker offers. */
class OnboardingPresetLogicTest {
    private val usRegion = RegionSelection(countryCode = "US", source = RegionSource.MANUAL)

    @Test
    fun selectablePresetsAreAlphabeticalAndDoNotPinTheRecommendation() {
        val recommended = assertNotNull(RadioPresets.recommended(usRegion))
        val usPresets = onboardingSelectablePresets(usRegion)
        assertEquals(usPresets.sortedBy { it.name }.map { it.id }, usPresets.map { it.id })
        assertEquals(1, usPresets.count { it.id == recommended.id })
        assertTrue(usPresets.contains(recommended))
    }

    @Test
    fun selectablePresetsGateCountyScopedEntriesToTheirRegion() {
        val usPresets = onboardingSelectablePresets(usRegion)
        assertTrue(usPresets.any { it.id == "us-ca" })
        // WCMesh is county-scoped; a bare US selection (no county) must not offer it.
        assertFalse(usPresets.any { it.id == "wcmesh" })
        // PhillyMesh and LVMesh are sub-region-scoped; a bare US selection (no state) must not offer them.
        assertFalse(usPresets.any { it.id == "phillymesh" })
        assertFalse(usPresets.any { it.id == "lvmesh" })
        // A US pick must not offer another continent's catalog (iOS presets(for:)).
        assertFalse(usPresets.any { it.id == "eu-narrow" })
        assertFalse(usPresets.any { it.id == "au-915" })

        val socal = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-CA",
            countyKey = "los angeles",
            source = RegionSource.LOCATION,
        )
        assertTrue(onboardingSelectablePresets(socal).any { it.id == "wcmesh" })
        val pennsylvania = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-PA",
            source = RegionSource.LOCATION,
        )
        assertTrue(onboardingSelectablePresets(pennsylvania).any { it.id == "phillymesh" })
        assertTrue(onboardingSelectablePresets(pennsylvania).any { it.id == "lvmesh" })
        assertFalse(onboardingSelectablePresets(pennsylvania).any { it.id == "wcmesh" })
        val delaware = RegionSelection(
            countryCode = "US",
            administrativeAreaCode = "US-DE",
            source = RegionSource.LOCATION,
        )
        assertTrue(onboardingSelectablePresets(delaware).any { it.id == "phillymesh" })
        assertFalse(onboardingSelectablePresets(delaware).any { it.id == "lvmesh" })
    }

    @Test
    fun selectablePresetsWithoutRegionOfferTheFullCatalogMinusCountyEntries() {
        val presets = onboardingSelectablePresets(null)
        assertTrue(presets.isNotEmpty())
        assertFalse(presets.any { it.id == "wcmesh" })
        assertFalse(presets.any { it.id == "phillymesh" })
        assertFalse(presets.any { it.id == "lvmesh" })
        assertFalse(presets.any { it.id == "au-qld" })
    }

    @Test
    fun flattenMarkdownLinksReplacesLinkSpansWithTheirUrl() {
        assertEquals(
            "Join and ask! https://meshcore.gg",
            flattenMarkdownLinks("Join and ask! [https://meshcore.gg](https://meshcore.gg)"),
        )
        assertEquals("no links here", flattenMarkdownLinks("no links here"))
    }

    @Test
    fun markdownLinkDisplayKeepsTheUrlRangeForATap() {
        val parsed = markdownLinkDisplay("Join and ask! [https://meshcore.gg](https://meshcore.gg)")
        assertEquals("Join and ask! https://meshcore.gg", parsed.text)
        assertEquals("https://meshcore.gg", parsed.url)
        assertEquals(
            "https://meshcore.gg",
            parsed.text.substring(parsed.urlStart, parsed.urlEnd),
        )
    }

    @Test
    fun markdownLinkRangePointsAtTheLinkWhenUrlAppearsEarlierAsPlainText() {
        val parsed = markdownLinkDisplay("https://meshcore.gg or [visit](https://meshcore.gg)")
        assertEquals("https://meshcore.gg or https://meshcore.gg", parsed.text)
        assertEquals("https://meshcore.gg or ".length, parsed.urlStart)
        assertEquals(parsed.text.length, parsed.urlEnd)
    }

    @Test
    fun aSuccessfulPresetApplyRunsTheSuccessWork() = runTest {
        var applied = false
        val outcome = applyOnboardingPreset(
            apply = {},
            onApplied = { applied = true },
            errorText = { "mapped" },
        )
        assertEquals(OnboardingPresetApply.Applied, outcome)
        assertTrue(applied)
    }

    @Test
    fun aRetryablePresetApplyReturnsTheMappedMessage() = runTest {
        var applied = false
        val outcome = applyOnboardingPreset(
            apply = { error("link down") },
            onApplied = { applied = true },
            errorText = { "mapped" },
        )
        assertEquals(OnboardingPresetApply.Retryable("mapped"), outcome)
        assertFalse(applied)
    }

    @Test
    fun aNonRetryablePresetApplyReturnsFailed() = runTest {
        var applied = false
        val outcome = applyOnboardingPreset(
            apply = { throw IllegalArgumentException("bad") },
            onApplied = { applied = true },
            errorText = { "mapped" },
        )
        assertEquals(OnboardingPresetApply.Failed("mapped"), outcome)
        assertFalse(applied)
    }

    @Test
    fun aFailureDuringCompletionStillMapsTheError() = runTest {
        var started = false
        val outcome = applyOnboardingPreset(
            apply = {},
            onApplied = {
                started = true
                throw IllegalArgumentException("complete")
            },
            errorText = { "mapped" },
        )
        assertTrue(started)
        assertEquals(OnboardingPresetApply.Failed("mapped"), outcome)
    }

    @Test
    fun aCancelledPresetApplyPropagatesBeforeSuccessWork() = runTest {
        var applied = false
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            applyOnboardingPreset(
                apply = { throw cancelled },
                onApplied = { applied = true },
                errorText = { "mapped" },
            )
        }
        assertSame(cancelled, thrown)
        assertFalse(applied)
    }

    @Test
    fun aPresetApplyErrorPropagatesBeforeSuccessWork() = runTest {
        var applied = false
        assertFailsWith<PresetProbeError> {
            applyOnboardingPreset(
                apply = { throw PresetProbeError() },
                onApplied = { applied = true },
                errorText = { "mapped" },
            )
        }
        assertFalse(applied)
    }
}

private class PresetProbeError : Error("probe")
