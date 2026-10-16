package one.meshcore.shared.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.GeoHttpClient
import one.meshcore.shared.map.GeoHttpResponse
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.ui.LocalMapLibreHostRenderer
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_location_picker_drop_pin
import one.meshcore.shared.resources.tools_line_of_sight_results
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.LosAnalysisStatus
import one.meshcore.shared.tools.LosPointId
import one.meshcore.shared.tools.analyze
import one.meshcore.shared.tools.configure
import one.meshcore.shared.tools.frameOnUser
import one.meshcore.shared.tools.setFrequencyMHz
import one.meshcore.shared.tools.updateAdditionalHeight
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineOfSightLifetimeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private val renderer = LosRecordingRenderer()
    private var room: NodeRemovalFixture? = null
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var dropLabel: String
    private lateinit var resultsLabel: String
    private lateinit var model: LineOfSightViewModel
    private val elevationReply = CompletableDeferred<GeoHttpResponse>()

    @Volatile private var elevationCalls = 0

    @After
    fun tearDown() {
        elevationReply.cancel()
        scope.cancel()
        room?.close()
    }

    @Test
    fun tabReturnRetainsPlacedPoint() {
        show()
        compose.onNodeWithText(dropLabel).performClick()
        compose.waitUntil(TIMEOUT_MS) { renderer.points.any { it.id == "los-point-a" } }
        tapTab(AppTab.CHATS)
        tapTab(AppTab.TOOLS)
        compose.waitUntil(TIMEOUT_MS) { renderer.points.any { it.id == "los-point-a" } }
        compose.runOnIdle { assertEquals(1, renderer.points.count { it.id == "los-point-a" }) }
    }

    @Test
    fun widthRemountRetainsPointsRfEditsAndFullCamera() {
        show()
        placePoint()
        compose.runOnIdle {
            model.updateAdditionalHeight(LosPointId.POINT_A, 25.0)
            model.setFrequencyMHz(915.0)
            renderer.idle?.invoke(REGION)
            shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH
        }
        compose.waitForIdle()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(25.0, model.pointA?.additionalHeight)
            assertEquals(915.0, model.frequencyMHz, 0.0)
            assertEquals(REGION, renderer.initialCamera)
            assertEquals(REGION, model.cameraRegion)
        }
        capturePayloadFrame("los-retained-camera.png")
    }

    @Test
    fun nativeBackAndReopenRetainPlacedPoint() {
        show()
        placePoint()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.runOnIdle { navigation.selectTool(ToolSelection.LINE_OF_SIGHT) }
        compose.waitUntil(TIMEOUT_MS) { renderer.points.any { it.id == "los-point-a" } }
    }

    @Test
    fun finishedUserPanRetainsRegionWithoutAnotherCommand() {
        show()
        val version = renderer.cameraVersion
        compose.runOnIdle { renderer.idle?.invoke(REGION) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(REGION, renderer.initialCamera)
            assertEquals(version, renderer.cameraVersion)
            assertEquals(null, renderer.cameraTarget)
        }
    }

    @Test
    fun programmaticLocateCenterIsUsedByRealDropControl() {
        show()
        compose.runOnIdle { model.frameOnUser(REGION.center) }
        compose.waitForIdle()
        placePoint()
        compose.runOnIdle { assertEquals(REGION.center, model.pointA?.coordinate) }
    }

    @Test
    fun pendingElevationCompletesWhileHiddenAndReturnKeepsIt() {
        show(pendingElevation = true)
        placePoint()
        compose.waitUntil(TIMEOUT_MS) { elevationCalls == 1 }
        tapTab(AppTab.CHATS)
        compose.runOnIdle { elevationReply.complete(GeoHttpResponse(200, "{\"elevation\":[123]}")) }
        compose.waitUntil(TIMEOUT_MS) { model.pointA?.groundElevation == 123.0 }
        tapTab(AppTab.TOOLS)
        compose.runOnIdle { assertEquals(123.0, model.pointA?.groundElevation) }
    }

    @Test
    fun mountedRfAndAnalysisPublishWithoutRemount() {
        show()
        placePoint()
        compose.onNodeWithText(dropLabel).performClick()
        compose.waitUntil(TIMEOUT_MS) { model.canAnalyze }
        compose.runOnIdle { model.setFrequencyMHz(915.0) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("915.0")).assertExists()
        compose.runOnIdle { model.analyze() }
        compose.waitUntil(TIMEOUT_MS) { model.analysisStatus is LosAnalysisStatus.Result }
        compose.waitForIdle()
        compose.onNodeWithText(resultsLabel).assertExists()
        tapTab(AppTab.CHATS)
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(resultsLabel).assertExists()
    }

    private fun placePoint() {
        compose.onNodeWithText(dropLabel).performClick()
        compose.waitUntil(TIMEOUT_MS) { renderer.points.any { it.id == "los-point-a" } }
    }

    private fun show(pendingElevation: Boolean = false) {
        val room = NodeRemovalFixture(compose.activity, navigation).also { this.room = it }
        compose.runOnIdle {
            compose.activity.enableEdgeToEdge()
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.LINE_OF_SIGHT)
        }
        val base = room.dependencies
        val elevation = ElevationService(object : GeoHttpClient {
            override suspend fun get(url: String): GeoHttpResponse {
                elevationCalls++
                if (pendingElevation) return elevationReply.await()
                val count = url.substringAfter("latitude=").substringBefore('&').split(',').size
                return GeoHttpResponse(200, "{\"elevation\":[${List(count) { "0" }.joinToString(",")}]}")
            }
        })
        model = LineOfSightViewModel(scope, elevationService = elevation, fetchContacts = { id ->
            base.contactService()?.getContacts(id)
                ?: emptyList()
        })
            .also { it.configure(base.currentRadioId) }
        val dependencies = ShellDependencies(
            base.session,
            base.services.copy(lineOfSightWorkspace = model),
            base.sharing,
            base.preferences,
            base.radio,
            base.maintenance,
        )
        shell = AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, dependencies)
        compose.setContent {
            dropLabel = stringResource(Res.string.settings_location_picker_drop_pin)
            resultsLabel = stringResource(Res.string.tools_line_of_sight_results)
            CompositionLocalProvider(LocalMapLibreHostRenderer provides renderer) { MaterialTheme { shell.Content() } }
        }
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(shell.tabLabels.getValue(tab)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private companion object {
        val REGION = MapCameraRegion(LatLng(38.0, -121.0), 0.3, 0.7)
        const val TIMEOUT_MS = 10_000L
    }
}
