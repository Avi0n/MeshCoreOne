// AndroidOnly: WP-503 Measure actual selected saved-endpoint text against native button paint across all effective theme frames.
package com.meshcoreone.android.accessibility

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
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
import com.meshcoreone.android.feature.tools.los.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLineOfSightContrastTest : DeviceAccessibilityHarness() {
    @Test fun selectedSavedEndpointTextClearsItsNativePaintInAll38EffectiveFrames() {
        val radio = RadioId(UUID(0, 311))
        val contact = ContactDTO(id = UUID(0, 312), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x77 }), name = "Saved endpoint",
            typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null,
            latitude = 37.8, longitude = -122.4)
        var reads = 0
        var elevations = 0
        val dependencies = LineOfSightFeatureDependencies(
            elevationSource = object : LineOfSightElevationSource {
                override suspend fun fetchElevation(coordinate: Coordinate): Double {
                    assertEquals(Coordinate(37.8, -122.4), coordinate)
                    elevations++
                    return 100.0
                }
                override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> =
                    error("Single endpoint selection must not request terrain analysis")
                override fun optimalSampleCount(distanceMeters: Double): Int = error("No analysis requested")
                override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                    error("No analysis requested")
            },
            pathAnalyzer = object : LineOfSightPathAnalyzer {
                override fun distanceMeters(from: Coordinate, to: Coordinate): Double = error("No analysis requested")
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
                listOf(contact)
            } },
            radioId = { radio }, deviceFrequencyKHz = { 906000u },
            failureReporter = LineOfSightFailureReporter { operation, failure ->
                throw AssertionError("Unexpected LOS contrast-fixture failure: $operation", failure)
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
        compose.onNodeWithText(contact.displayName).performScrollTo().performClick()
        compose.waitUntil { elevations == 1 }
        val button = compose.onNode(hasText(contact.displayName) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
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
                    button.performScrollTo()
                    val text = compose.onNode(hasText(contact.displayName) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                        hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)), useUnmergedTree = true)
                    assertFont200Unclipped(text)
                    val foreground = textLayout(text).layoutInput.style.color
                    assertTrue("The real endpoint label must resolve its emitted foreground", foreground.isSpecified)
                    val background = paintedBackground(text, Color(0xFF1677FF))
                    val ratio = WCAGContrast.contrastRatio(foreground.toThemeColor(), background)
                    val frame = "${candidate.id.rawValue}:$scheme:hc=$contrast"
                    println("WP503_LOS_SELECTED_CONTRAST|frame=$frame|ratio=$ratio|minimum=4.5|realRadio=false")
                    if (ratio < 4.5) failures += "$frame=$ratio<4.5"
                    frames++
                }
            }
        }
        assertEquals("Do not silently reduce the effective theme/scheme/contrast family", 38, frames)
        assertEquals(1, reads)
        assertEquals(1, elevations)
        assertTrue("Selected endpoint text fails unchanged 4.5:1 contrast: ${failures.joinToString()}", failures.isEmpty())
    }
}
