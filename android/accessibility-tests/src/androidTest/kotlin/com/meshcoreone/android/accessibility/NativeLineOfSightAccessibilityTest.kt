// AndroidOnly: WP-503 Actual platform accessibility actions for saved LOS endpoints, without marker gestures or fabricated analysis.
package com.meshcoreone.android.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
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
class NativeLineOfSightAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun savedRepeatersExposeNamedNativeActionsForNonGestureEndpointSelectionAtFont200() {
        val radio = RadioId(UUID(0, 301))
        val east = ContactDTO(id = UUID(0, 302), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x55 }), name = "East saved repeater",
            typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null,
            latitude = 37.8, longitude = -122.4)
        val west = ContactDTO(id = UUID(0, 303), radioId = radio,
            publicKey = Bytes(ByteArray(32) { 0x66 }), name = "\u4e2d\u6587\u897f\u5074\u4e2d\u7e7c",
            typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null,
            latitude = 37.7, longitude = -122.3)
        var catalogReads = 0
        var elevationReads = 0
        val dependencies = LineOfSightFeatureDependencies(
            elevationSource = object : LineOfSightElevationSource {
                override suspend fun fetchElevation(coordinate: Coordinate): Double {
                    assertEquals(Coordinate(east.latitude, east.longitude), coordinate)
                    elevationReads++
                    return 100.0
                }
                override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> =
                    error("No terrain analysis requested")
                override fun optimalSampleCount(distanceMeters: Double): Int = error("No terrain analysis requested")
                override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                    error("No terrain analysis requested")
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
            contactSource = { LineOfSightContactSource { requestedRadio ->
                assertEquals(radio, requestedRadio)
                catalogReads++
                listOf(east, west)
            } },
            radioId = { radio }, deviceFrequencyKHz = { 906000u },
            failureReporter = LineOfSightFailureReporter { operation, failure ->
                throw AssertionError("Unexpected LOS failure: $operation", failure)
            },
        )
        content { MeshCoreTheme(motionScale = 0f) { AuditSurface { LineOfSightEntry(dependencies) } } }
        compose.waitUntil { catalogReads == 1 }
        onView(withContentDescription(activity.getString(T.toolsLineOfSight))).check(matches(isDisplayed()))
        assertScrollableFont200Unclipped(compose.onNode(hasText(activity.getString(T.toolsLineOfSight)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)))
        val root = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow) {
            "The real native test activity must own a focused accessibility window"
        }
        assertEquals(activity.packageName, root.packageName.toString())
        for (contact in listOf(east, west)) {
            val named = root.findAccessibilityNodeInfosByText(contact.displayName)
            val actionable = named.filter { it.isClickable }
            println("WP503_LOS_PLATFORM_ACTIONS|label=${contact.displayName}|matches=${named.size}|clickable=${actionable.size}" +
                "|catalogReads=$catalogReads|elevationReads=$elevationReads|realRadio=false")
            assertTrue("Saved LOS repeater '${contact.displayName}' must expose a named native accessibility click " +
                "or non-gesture list action, independent of map tiles", actionable.isNotEmpty())
        }
        val selected = root.findAccessibilityNodeInfosByText(east.displayName).first { it.isClickable }
        if (!selected.isVisibleToUser) {
            assertTrue("An offscreen saved endpoint must be reachable without a map gesture",
                selected.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id))
        }
        assertTrue(selected.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id))
        compose.waitUntil { elevationReads == 1 }
        assertScrollableFont200Unclipped(compose.onNode(hasText(east.displayName) and
            !SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)))
        assertVisibleClickTargetsAtLeast48Dp()
        assertEquals(1, catalogReads)
        assertEquals(1, elevationReads)
    }
}
