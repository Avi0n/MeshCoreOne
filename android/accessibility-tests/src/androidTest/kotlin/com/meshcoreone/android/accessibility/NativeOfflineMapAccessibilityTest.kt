// AndroidOnly: WP-503 Audit actual MapEntry offline panel states/actions with the native renderer and deterministic offline backend ports.
package com.meshcoreone.android.accessibility

import android.view.View
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog as isViewDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot as isViewRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.feature.map.MapEntry
import com.meshcoreone.android.feature.map.MapFeatureContent
import com.meshcoreone.android.feature.map.MapFeatureDataSource
import com.meshcoreone.android.feature.map.MapFeatureDependencies
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeOfflineMapAccessibilityTest : DeviceAccessibilityHarness() {
    private val packId = UUID(0, 503501)
    private val regionName = "Region \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd"
    private val title get() = activity.getString(R.string.l10n_app_settings_offlinemaps_title)
    private val done get() = activity.getString(R.string.l10n_app_map_map_common_done)
    private val download get() = activity.getString(R.string.l10n_app_settings_offlinemaps_downloadregion)
    private val regionLabel get() = activity.getString(R.string.l10n_app_settings_offlinemaps_regionname)
    private val empty get() = activity.getString(R.string.l10n_app_settings_offlinemaps_emptydescription)
    private val pause get() = activity.getString(R.string.l10n_app_settings_offlinemaps_pause)
    private val resume get() = activity.getString(R.string.l10n_app_settings_offlinemaps_resume)
    private val delete get() = activity.getString(R.string.l10n_app_localizable_common_delete)
    private val cancel get() = activity.getString(R.string.l10n_app_settings_offlinemaps_cancel)
    private val scrollScope = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    @get:Rule(order = 1) val failureDiagnostics = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            println("WP503_OFFLINE_MAP_FAILURE|case=${description.methodName}|failure=${failure.javaClass.name}")
            println("WP503_OFFLINE_MAP_FAILURE_TREE|" +
                compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
        }
    }

    @Test fun emptyPanelTypedDiskAndNetworkFailuresStayReadableAndRetryStartsOnlyTheNamedRegion() {
        val backend = Backend()
        var bytes = 1L
        var network = true
        mount(backend, { bytes }, { network })
        label(title)
        label(empty)
        panelAction(download).assertIsNotEnabled()
        val field = compose.onNode(hasSetTextAction() and hasText(regionLabel)).performScrollTo()
        assertTarget(field)
        label(regionLabel)
        field.performTextReplacement("  $regionName  ")
        val start = panelAction(download).assertIsEnabled()
        start.performClick()
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_error_insufficientdiskspace), dialog = true)
        assertTrue(backend.requests.isEmpty())
        dialogAction(done).performClick()
        bytes = Long.MAX_VALUE
        network = false
        panelAction(download).performClick()
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_nonetwork), dialog = true)
        assertTrue(backend.requests.isEmpty())
        dialogAction(done).performClick()
        network = true
        panelAction(download).performClick()
        compose.runOnIdle {
            assertEquals(1, backend.requests.size)
            assertEquals(regionName, backend.requests.single().name)
            assertEquals(packId, backend.requests.single().id)
            assertEquals(OfflineLayer.BASE, backend.requests.single().layer)
        }
        label(regionName)
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_downloading))
        assertProgress(0f)
        closePanel()
        println("WP503_OFFLINE_MAP_DOWNLOAD|diskRejected=true|networkRejected=true|requests=1|trimmedName=true|realDownload=false")
    }

    @Test fun downloadingPackHasTextAndMeasuredProgressWithPauseResumeAndConfirmedDeleteCancellation() {
        val backend = Backend(listOf(pack(OfflinePackState.DOWNLOADING, 0.25f)))
        mount(backend)
        label(regionName)
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_downloading))
        assertProgress(0.25f)
        panelAction(pause).performClick()
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_paused))
        panelAction(resume).performClick()
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_downloading))
        panelAction(delete).performClick()
        label(regionName, dialog = true)
        dialogAction(cancel).performClick()
        assertTrue(backend.deleted.isEmpty())
        panelAction(delete).performClick()
        dialogAction(delete).performClick()
        label(empty)
        compose.runOnIdle {
            assertEquals(listOf(packId), backend.paused)
            assertEquals(listOf(packId), backend.resumed)
            assertEquals(listOf(packId), backend.deleted)
        }
        closePanel()
        println("WP503_OFFLINE_MAP_LIFECYCLE|pause=1|resume=1|cancelDelete=0|confirmedDelete=1|exactPack=true|realBackend=false")
    }

    @Test fun completedPackKeepsItsTextAndFullProgressWithoutPauseOrResumeAndDoneDoesNotDelete() {
        val backend = Backend(listOf(pack(OfflinePackState.COMPLETE, 1f)))
        mount(backend)
        label(regionName)
        label(activity.getString(R.string.l10n_app_settings_offlinemaps_complete))
        assertProgress(1f)
        compose.onNode(hasText(pause) and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText(resume) and hasClickAction()).assertDoesNotExist()
        panelAction(delete)
        closePanel()
        assertTrue(backend.deleted.isEmpty())
        assertEquals(listOf(packId), backend.packs.value.map { it.id })
        println("WP503_OFFLINE_MAP_COMPLETE|progress=1.0|pauseResumeAbsent=true|doneDeletes=0")
    }

    private fun mount(backend: Backend, bytes: () -> Long = { Long.MAX_VALUE }, network: () -> Boolean = { true }) {
        assertTrue("Keep the real production MapLibre architecture guard", MapLibreRuntime.isSupported)
        val controller = OfflineMapController(
            backend,
            mapOf(OfflineLayer.BASE to OfflineLayerPolicy(OfflineLayer.BASE, 14, MapLibreOpenFreeMap.attribution)),
            bytes, network, { Instant.parse("2026-10-14T00:00:00Z") }, { packId },
        )
        val dependencies = MapFeatureDependencies(MapFeatureDataSource { MapFeatureContent() }, controller)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    MapEntry(FeatureRoute(FeatureId.MAP), { error("Unexpected map navigation") }, dependencies,
                        focus = GeoPoint(37.8, -122.4))
                }
            }
        }
        val open = compose.onNode(hasText(title) and hasClickAction())
        assertFont200Unclipped(compose.onNode(hasText(title) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true))
        assertTarget(open)
        open.performClick()
    }

    private fun label(text: String, dialog: Boolean = false) {
        val scope = if (dialog) hasAnyAncestor(isDialog()) else scrollScope
        val node = compose.onNode(hasText(text) and scope and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        if (!dialog) {
            node.performScrollTo()
            scrollWholeAllocationIntoView(node)
        }
        assertFont200Unclipped(node.assertIsDisplayed())
        val measured = node.fetchSemanticsNode()
        val viewport = nativeWindowViewport(compose, if (dialog) dialogRoot() else activity.window.decorView, measured)
        println("WP503_OFFLINE_MAP_TEXT|label=$text|bounds=${measured.boundsInWindow}|size=${measured.size}|viewport=$viewport")
        val bounds = measured.boundsInWindow
        assertTrue("The complete offline map label must be visible",
            bounds.left >= viewport.left && bounds.top >= viewport.top && bounds.right <= viewport.right &&
                bounds.bottom <= viewport.bottom && bounds.width == measured.size.width.toFloat() &&
                bounds.height == measured.size.height.toFloat())
    }

    private fun panelAction(text: String): SemanticsNodeInteraction {
        val node = compose.onNode(hasText(text) and hasClickAction() and scrollScope).performScrollTo()
        scrollWholeAllocationIntoView(node)
        label(text)
        assertTarget(node)
        return node
    }

    private fun dialogAction(text: String): SemanticsNodeInteraction {
        val node = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
        label(text, dialog = true)
        assertTarget(node, dialog = true)
        return node
    }

    private fun assertTarget(interaction: SemanticsNodeInteraction, dialog: Boolean = false) {
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        var viewport = nativeWindowViewport(compose, if (dialog) dialogRoot() else activity.window.decorView, node)
        generateSequence(node.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }?.let { viewport = viewport.intersect(it.boundsInWindow) }
        val target = node.touchBoundsInRoot.translate(node.boundsInWindow.topLeft - node.boundsInRoot.topLeft)
        val density = activity.resources.displayMetrics.density
        println("WP503_OFFLINE_MAP_TARGET|bounds=$target|viewport=$viewport")
        assertTrue("Offline map actions must be fully visible and at least48dp",
            target.left >= viewport.left && target.top >= viewport.top && target.right <= viewport.right &&
                target.bottom <= viewport.bottom && target.width / density >= 48f && target.height / density >= 48f)
    }

    private fun assertProgress(value: Float) {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and scrollScope
        compose.onAllNodes(matcher).assertCountEquals(1)
        val node = compose.onNode(matcher).performScrollTo().fetchSemanticsNode()
        assertEquals(ProgressBarRangeInfo(value, 0f..1f), node.config[SemanticsProperties.ProgressBarRangeInfo])
        println("WP503_OFFLINE_MAP_PROGRESS|current=$value|range=0..1|dataBound=true")
    }

    private fun closePanel() {
        panelAction(done).assertIsEnabled().performClick()
        compose.onNode(hasText(regionLabel) and hasSetTextAction()).assertDoesNotExist()
        compose.onNode(hasText(title) and hasClickAction()).assertIsDisplayed()
    }

    private fun dialogRoot(): View {
        var root: View? = null
        onView(isViewRoot()).inRoot(isViewDialog()).check { view, missing ->
            if (missing != null) throw missing
            root = requireNotNull(view)
        }
        return requireNotNull(root)
    }

    private fun pack(state: OfflinePackState, fraction: Float) = OfflinePack(
        packId, regionName, OfflineLayer.BASE, GeoBounds(GeoPoint(37.7, -122.5), GeoPoint(37.9, -122.3)),
        10, 14, Instant.parse("2026-10-14T00:00:00Z"), state, fraction,
    )

    private inner class Backend(initial: List<OfflinePack> = emptyList()) : OfflineMapBackend {
        override val packs = MutableStateFlow(initial)
        val requests = mutableListOf<OfflinePackRequest>()
        val paused = mutableListOf<UUID>()
        val resumed = mutableListOf<UUID>()
        val deleted = mutableListOf<UUID>()
        override suspend fun createAndStart(request: OfflinePackRequest) {
            requests += request
            packs.value = listOf(OfflinePack(
                request.id, request.name, request.layer, request.bounds, request.minZoom, request.maxZoom,
                request.createdAt, OfflinePackState.DOWNLOADING, 0f,
            ))
        }
        override suspend fun pause(packId: UUID) {
            paused += packId
            packs.value = packs.value.map { if (it.id == packId) it.copy(state = OfflinePackState.PAUSED) else it }
        }
        override suspend fun resume(packId: UUID) {
            resumed += packId
            packs.value = packs.value.map { if (it.id == packId) it.copy(state = OfflinePackState.DOWNLOADING) else it }
        }
        override suspend fun delete(packId: UUID) {
            deleted += packId
            packs.value = packs.value.filterNot { it.id == packId }
        }
    }
}
