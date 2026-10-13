// AndroidOnly: WP-503 Measure the distinct selected Point B label against native green paint without editing the Point A/action fixtures.
package com.meshcoreone.android.accessibility

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.diagnostics.rf.RFCalculator
import com.meshcoreone.android.feature.tools.los.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLineOfSightPointBContrastTest : DeviceAccessibilityHarness() {
    @Test fun selectedPointBLabelClearsItsNativeGreenPaintInAll38EffectiveFrames() {
        val radio = RadioId(UUID(0, 321))
        val first = ContactDTO(id = UUID(0, 322), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x11 }), name = "First endpoint",
            typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null,
            latitude = 37.8, longitude = -122.4)
        val second = first.copy(id = UUID(0, 323), name = "Second endpoint",
            publicKey = Bytes(ByteArray(32) { 0x22 }), latitude = 37.801)
        var reads = 0
        val elevations = mutableListOf<Coordinate>()
        val dependencies = LineOfSightFeatureDependencies(
            elevationSource = object : LineOfSightElevationSource {
                override suspend fun fetchElevation(coordinate: Coordinate): Double {
                    elevations += coordinate
                    return when (coordinate) {
                        Coordinate(first.latitude, first.longitude) -> 100.0
                        Coordinate(second.latitude, second.longitude) -> 200.0
                        else -> error("Unexpected selected endpoint")
                    }
                }
                override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> =
                    error("Endpoint selection must not request terrain analysis")
                override fun optimalSampleCount(distanceMeters: Double): Int = error("No analysis requested")
                override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                    error("No analysis requested")
            },
            pathAnalyzer = object : LineOfSightPathAnalyzer {
                override fun distanceMeters(from: Coordinate, to: Coordinate): Double = RFCalculator.distance(from, to)
                override fun analyzePath(
                    elevationProfile: List<ElevationSample>, pointAHeightMeters: Double, pointBHeightMeters: Double,
                    frequencyMHz: Double, refractionK: Double,
                ): PathAnalysisResult = error("No analysis requested")
                override fun analyzePathSegment(
                    elevationProfile: List<ElevationSample>, startHeightMeters: Double, endHeightMeters: Double,
                    frequencyMHz: Double, refractionK: Double,
                ): PathAnalysisResult = error("No analysis requested")
            },
            contactSource = { LineOfSightContactSource {
                assertEquals(radio, it)
                reads++
                listOf(first, second)
            } },
            radioId = { radio }, deviceFrequencyKHz = { 906000u },
            failureReporter = LineOfSightFailureReporter { operation, failure ->
                throw AssertionError("Unexpected Point B fixture failure: $operation", failure)
            },
        )
        val theme = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
        val high = mutableStateOf(false)
        content {
            MeshCoreTheme(theme.value, preference.value, highContrast = high.value, motionScale = 0f) {
                AuditSurface { LineOfSightEntry(dependencies) }
            }
        }
        compose.waitUntil { reads == 1 }
        fun button(name: String) = compose.onNode(hasText(name) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        button(first.name).performScrollTo().performClick()
        compose.waitUntil { elevations.size == 1 }
        button(second.name).performScrollTo().performClick()
        compose.waitUntil { elevations.size == 2 }
        val failures = mutableListOf<String>()
        var frames = 0
        for (candidate in ThemeRegistry.allThemes) {
            for (scheme in ColorScheme.entries.filter {
                candidate.preferredColorScheme == null || candidate.preferredColorScheme == it
            }) {
                for (contrast in listOf(false, true)) {
                    compose.runOnIdle {
                        theme.value = candidate
                        preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                        high.value = contrast
                    }
                    button(second.name).performScrollTo()
                    val text = compose.onNode(hasText(second.name) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                        hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)), useUnmergedTree = true)
                    assertFont200Unclipped(text)
                    val foreground = textLayout(text).layoutInput.style.color
                    assertTrue("Point B must resolve its actual label foreground", foreground.isSpecified)
                    val background = paintedBackground(text, Color(0xFF1B8A5A))
                    val ratio = WCAGContrast.contrastRatio(foreground.toThemeColor(), background)
                    val frame = "${candidate.id.rawValue}:$scheme:hc=$contrast"
                    println("WP503_LOS_POINT_B_CONTRAST|frame=$frame|ratio=$ratio|minimum=4.5|realRadio=false")
                    if (ratio < 4.5) failures += "$frame=$ratio<4.5"
                    frames++
                }
            }
        }
        assertEquals(38, frames)
        assertEquals(1, reads)
        assertEquals(listOf(Coordinate(first.latitude, first.longitude), Coordinate(second.latitude, second.longitude)), elevations)
        assertTrue("Point B label fails unchanged4.5:1 contrast: ${failures.joinToString()}", failures.isEmpty())
    }
}
