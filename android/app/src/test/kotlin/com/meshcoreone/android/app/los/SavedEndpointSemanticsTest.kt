// AndroidOnly: WP-315 Mounted saved-endpoint selection semantics and real holder actions; no terrain or radio evidence.
package com.meshcoreone.android.app.los

import android.content.res.Configuration
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.diagnostics.rf.RFCalculator
import com.meshcoreone.android.feature.tools.los.ElevationSample
import com.meshcoreone.android.feature.tools.los.LineOfSightContactSource
import com.meshcoreone.android.feature.tools.los.LineOfSightElevationSource
import com.meshcoreone.android.feature.tools.los.LineOfSightEntry
import com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies
import com.meshcoreone.android.feature.tools.los.LineOfSightPathAnalyzer
import com.meshcoreone.android.feature.tools.los.PathAnalysisResult
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w900dp-h1200dp-mdpi", shadows = [UnsupportedMapProcess::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedEndpointSemanticsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000315"))
    private val east = contact("East saved repeater", 1, 37.8)
    private val west = contact("\u897f saved repeater", 2, 37.7)
    private val elevationReads = mutableListOf<Coordinate>()
    private val dependencies = LineOfSightFeatureDependencies(
        elevationSource = object : LineOfSightElevationSource {
            override suspend fun fetchElevation(coordinate: Coordinate): Double {
                elevationReads += coordinate
                return 100.0 // Synthetic endpoint height only; never a terrain analysis result.
            }
            override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> =
                error("Endpoint selection must not request terrain analysis")
            override fun optimalSampleCount(distanceMeters: Double): Int =
                error("Endpoint selection must not sample terrain")
            override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                error("Endpoint selection must not sample terrain")
        },
        pathAnalyzer = object : LineOfSightPathAnalyzer {
            override fun distanceMeters(from: Coordinate, to: Coordinate): Double = RFCalculator.distance(from, to)
            override fun analyzePath(
                elevationProfile: List<ElevationSample>, pointAHeightMeters: Double,
                pointBHeightMeters: Double, frequencyMHz: Double, refractionK: Double,
            ): PathAnalysisResult = error("Endpoint selection must not analyze terrain")
            override fun analyzePathSegment(
                elevationProfile: List<ElevationSample>, startHeightMeters: Double,
                endHeightMeters: Double, frequencyMHz: Double, refractionK: Double,
            ): PathAnalysisResult = error("Endpoint selection must not analyze terrain")
        },
        contactSource = { LineOfSightContactSource { requestedRadio ->
            assertEquals(radio, requestedRadio)
            listOf(east, west)
        } },
        radioId = { radio },
        deviceFrequencyKHz = { null },
    )

    @Suppress("DEPRECATION")
    @Before fun mount() {
        val resources = RuntimeEnvironment.getApplication().resources
        resources.updateConfiguration(
            Configuration(resources.configuration).apply { fontScale = 2f },
            resources.displayMetrics,
        )
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().setContent {
            MeshCoreTheme(ThemeRegistry.default, systemDark = false, motionScale = 0f) {
                Box(Modifier.width(900.dp).height(1200.dp)) {
                    LineOfSightEntry(dependencies)
                }
            }
        }
    }

    @After fun dispose() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    @Test fun namedActionsAnnounceUnselectedPointAAndPointBThenClear() = exerciseEndpointActions()

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h1200dp-mdpi")
    fun endpointStateUsesLocalizedLabels() {
        assertEquals("de", host.get().resources.configuration.locales[0].language)
        exerciseEndpointActions()
    }

    private fun exerciseEndpointActions() {
        assertEquals(2f, host.get().resources.configuration.fontScale, 0f)
        assertEndpoint(east, AppToolsStrings.toolsLineOfSightNotSelected, selected = false)
        assertEndpoint(west, AppToolsStrings.toolsLineOfSightNotSelected, selected = false)
        button(east).performScrollTo().performClick()
        assertEndpoint(east, AppToolsStrings.toolsLineOfSightPointA, selected = true)
        assertEndpoint(west, AppToolsStrings.toolsLineOfSightNotSelected, selected = false)
        button(west).performScrollTo().performClick()
        assertEndpoint(east, AppToolsStrings.toolsLineOfSightPointA, selected = true)
        assertEndpoint(west, AppToolsStrings.toolsLineOfSightPointB, selected = true)
        compose.runOnIdle { assertEquals(listOf(Coordinate(37.8, -122.4), Coordinate(37.7, -122.4)), elevationReads) }
        button(east).performScrollTo().performClick()
        assertEndpoint(east, AppToolsStrings.toolsLineOfSightNotSelected, selected = false)
        assertEndpoint(west, AppToolsStrings.toolsLineOfSightPointB, selected = true)
        button(west).performScrollTo().performClick()
        assertEndpoint(west, AppToolsStrings.toolsLineOfSightNotSelected, selected = false)
        compose.runOnIdle { assertEquals(2, elevationReads.size) }
    }

    private fun button(contact: ContactDTO) = compose.onNode(hasText(contact.displayName) and hasClickAction())

    private fun assertEndpoint(contact: ContactDTO, label: Int, selected: Boolean) {
        val node = button(contact).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, host.get().getString(label)))
        if (selected) node.assertIsSelected() else node.assertIsNotSelected()
        assertEquals(listOf(contact.displayName), node.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
    }

    private fun contact(name: String, key: Int, latitude: Double) = ContactDTO(
        id = UUID(0, key.toLong()), radioId = radio, publicKey = Bytes(ByteArray(32) { key.toByte() }),
        name = name, typeRawValue = ContactType.REPEATER.rawValue,
        latitude = latitude, longitude = -122.4, lastHeardTimestamp = null,
    )
}

// Exercise the production unsupported-map path: JVM tests cannot load MapLibre's Android native library.
@Implements(Process::class)
class UnsupportedMapProcess : ShadowProcess() {
    companion object {
        @Implementation
        @JvmStatic
        fun is64Bit(): Boolean = false
    }
}
