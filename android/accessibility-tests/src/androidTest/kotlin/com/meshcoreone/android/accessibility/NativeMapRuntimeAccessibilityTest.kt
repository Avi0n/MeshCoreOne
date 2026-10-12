// AndroidOnly: WP-503 Attempt real public MapLibre/LoS entries without bypassing architecture/JNI or substituting a renderer.
package com.meshcoreone.android.accessibility

import android.os.Build
import android.os.Process
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.maps.UnavailableOfflineMapBackend
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.feature.map.MapEntry
import com.meshcoreone.android.feature.map.MapFeatureDataSource
import com.meshcoreone.android.feature.map.MapFeatureContent
import com.meshcoreone.android.feature.map.MapFeatureDependencies
import com.meshcoreone.android.feature.map.MapFeatureMarker
import com.meshcoreone.android.feature.tools.los.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeMapRuntimeAccessibilityTest : DeviceAccessibilityHarness() {
    @Test fun populatedNativeMapListSelectionKeepsCoordinatesAndFavoriteFilterReachableAtFont200() {
        val favoriteName = "\u4e2d\u6587\u6e2c\u8a66\u4e2d\u7e7c\u7bc0\u9ede"
        val otherName = "Other saved repeater"
        val favorite = MapMarker(UUID(0, 201), GeoPoint(37.8, -122.4),
            MapPinStyle.CONTACT_REPEATER, favoriteName)
        val other = MapMarker(UUID(0, 202), GeoPoint(37.7, -122.3),
            MapPinStyle.CONTACT_REPEATER, otherName)
        val dependencies = MapFeatureDependencies(
            MapFeatureDataSource { MapFeatureContent(markers = listOf(
                MapFeatureMarker(favorite, ContactMapType.REPEATER, true, false),
                MapFeatureMarker(other, ContactMapType.REPEATER, false, false),
            )) },
            OfflineMapController(UnavailableOfflineMapBackend, emptyMap(),
                { error("No offline download requested") },
                { error("No offline download requested") }),
        )
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface { MapEntry(FeatureRoute(FeatureId.MAP), { error("Unexpected navigation") }, dependencies) }
            }
        }
        val listTitle = activity.getString(R.string.l10n_app_contacts_contacts_trace_mode_list)
        val list = compose.onNodeWithText(listTitle)
        val measured = list.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val density = activity.resources.displayMetrics.density
        println("WP503_MAP_LIST_CONTROL|bounds=${measured.boundsInRoot}|size=${measured.size}|target=${measured.touchBoundsInRoot}|viewport=$root")
        list.assertIsDisplayed().assertIsNotSelected()
        assertFont200Unclipped(compose.onNodeWithText(listTitle, useUnmergedTree = true))
        val target = measured.touchBoundsInRoot
        assertTrue("The non-gesture list control must retain its entire 48dp target",
            target.width / density >= 48f && target.height / density >= 48f &&
                target.left >= root.left && target.right <= root.right &&
                target.top >= root.top && target.bottom <= root.bottom)
        list.performClick().assertIsSelected()
        val favoriteRow = compose.onNodeWithText(favoriteName).assertIsDisplayed()
        assertFont200Unclipped(compose.onNodeWithText(favoriteName, useUnmergedTree = true))
        compose.onNodeWithText(otherName).assertIsDisplayed()
        favoriteRow.performClick()
        list.performClick().assertIsNotSelected()
        assertFont200Unclipped(compose.onNodeWithText(favoriteName).assertIsDisplayed())
        assertFont200Unclipped(compose.onNodeWithText("37.8, -122.4").assertIsDisplayed())
        compose.onNodeWithText(activity.getString(R.string.l10n_app_map_map_common_done)).performClick()
        compose.onNodeWithText("37.8, -122.4").assertDoesNotExist()
        val favorites = compose.onNodeWithText(activity.getString(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites))
        favorites.performClick().assertIsSelected()
        list.performClick()
        compose.onNodeWithText(favoriteName).assertIsDisplayed()
        compose.onNodeWithText(otherName).assertDoesNotExist()
        list.performClick()
        favorites.performClick().assertIsNotSelected()
    }

    @Test fun actualNativeMapEntryExposesReachableRefreshAndConsumesPendingFocus() {
        observeRuntime()
        var loads = 0
        var consumed = 0
        val dependencies = MapFeatureDependencies(
            MapFeatureDataSource { loads++; MapFeatureContent() },
            OfflineMapController(UnavailableOfflineMapBackend, emptyMap(),
                { error("No offline download requested") },
                { error("No offline download requested") }),
        )
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    MapEntry(FeatureRoute(FeatureId.MAP), { error("Unexpected cross-feature route") },
                        dependencies, focus = GeoPoint(1.0, 2.0), onFocusConsumed = { consumed++ })
                }
            }
        }
        val refresh = compose.onNodeWithText(activity.getString(R.string.l10n_app_map_map_controls_refresh))
            .assertIsDisplayed()
        assertFont200Unclipped(refresh)
        assertVisibleClickTargetsAtLeast48Dp()
        assertEquals(1, loads)
        assertEquals(1, consumed)
    }

    @Test fun actualNativeLosEntryKeepsEmptyPointsTextualWithoutFabricatedTerrainOrAnalysis() {
        observeRuntime()
        val dependencies = LineOfSightFeatureDependencies(
            elevationSource = object : LineOfSightElevationSource {
                override suspend fun fetchElevation(coordinate: Coordinate): Double = error("No real endpoint selected")
                override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> = error("No real path selected")
                override fun optimalSampleCount(distanceMeters: Double): Int = error("No real path selected")
                override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                    error("No real path selected")
            },
            pathAnalyzer = object : LineOfSightPathAnalyzer {
                override fun distanceMeters(from: Coordinate, to: Coordinate): Double = error("No real endpoints selected")
                override fun analyzePath(
                    elevationProfile: List<ElevationSample>, pointAHeightMeters: Double, pointBHeightMeters: Double,
                    frequencyMHz: Double, refractionK: Double,
                ): PathAnalysisResult = error("No real path selected")
                override fun analyzePathSegment(
                    elevationProfile: List<ElevationSample>, startHeightMeters: Double, endHeightMeters: Double,
                    frequencyMHz: Double, refractionK: Double,
                ): PathAnalysisResult = error("No real segment selected")
            },
            contactSource = { null }, radioId = { null }, deviceFrequencyKHz = { null },
            failureReporter = LineOfSightFailureReporter { operation, error ->
                throw AssertionError("Unexpected LOS failure: $operation", error)
            },
        )
        content { MeshCoreTheme(motionScale = 0f) { AuditSurface { LineOfSightEntry(dependencies) } } }
        onView(withContentDescription(activity.getString(T.toolsLineOfSight))).check(matches(isDisplayed()))
        val heading = compose.onNode(hasText(activity.getString(T.toolsLineOfSight)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertScrollableFont200Unclipped(heading)
        val points = compose.onAllNodes(hasText(activity.getString(T.toolsLineOfSightNotSelected)),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("Both real endpoints must remain explicitly unselected", 2, points.size)
        for (point in points) {
            assertScrollableFont200Unclipped(compose.onNode(SemanticsMatcher("Actual unselected endpoint") {
                it.id == point.id
            }, useUnmergedTree = true))
        }
        compose.onNodeWithText(activity.getString(T.toolsLineOfSightAnalyze)).assertDoesNotExist()
    }

    private fun observeRuntime() {
        println("WP503_NATIVE_MAP_CAPABILITY|sdk=${Build.VERSION.SDK_INT}|process64=${Process.is64Bit()}|supported=${MapLibreRuntime.isSupported}")
        assertEquals("Use the real production architecture guard", Process.is64Bit(), MapLibreRuntime.isSupported)
    }
}
