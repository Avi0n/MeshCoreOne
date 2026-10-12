// AndroidOnly: WP-503 Public remote-node offline/catalog/history flows with deterministic read-only ports and native non-gesture chart selection.
package com.meshcoreone.android.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRemoteNodesAccessibilityTest : NativeAccessibilityHarness() {
    private val radio = RadioId(UUID(0, 101))
    private val contact = ContactDTO(id = UUID(0, 102), radioId = radio,
        publicKey = Bytes(ByteArray(32) { 0x33 }), name = "\u4e2d\u6587 remote",
        typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null)

    @Test fun catalogFailureIsTextualAndRefreshRecoversTheActualOfflineRemoteRow() {
        val rig = Rig().apply { catalogFailure = true }
        mount(rig)
        val failure = compose.onNodeWithTag("remote-error").assertIsDisplayed()
        assertFont200Unclipped(failure)
        assertTrue(failure.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals(1, rig.catalogReads)
        compose.runOnIdle { rig.catalogFailure = false }
        compose.onNodeWithText(host.get().getString(L.remoteNodesStatusRefresh)).performClick()
        compose.onNode(hasText(contact.displayName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        compose.onNodeWithText(host.get().getString(L.remoteNodesRoomDisconnectedBanner)).assertIsDisplayed()
        assertVisibleClickTargetsAtLeast48Dp()
        assertEquals(2, rig.catalogReads)
    }

    @Test fun offlineSavedHistoryExposesActualVoltageAndNonGestureSampleSelectionAtFont200() {
        val rig = Rig()
        mount(rig)
        openHistory()
        val title = host.get().getString(L.remoteNodesHistoryBattery)
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            hasContentDescription(title, substring = true)).performScrollTo().assertIsDisplayed()
        val measured = slider.fetchSemanticsNode()
        val density = host.get().resources.displayMetrics.density
        println("WP503_REMOTE_HISTORY_TARGET|bounds=${measured.touchBoundsInRoot}|size=${measured.size}")
        assertTrue("The current sample must expose the real latest voltage and unit",
            measured.config[SemanticsProperties.ContentDescription].any { it.contains("3.9 V") })
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        assertTrue("Native accessibility progress must select the actual earlier sample",
            slider.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].any { it.contains("3.7 V") })
        compose.onNodeWithTag("remote-history-content").performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, 64f))
        }
        val pointer = slider.fetchSemanticsNode()
        val extension = ((48f * density - pointer.size.height) / 2f).coerceAtLeast(0f)
        val centerX = pointer.size.width * .8f
        val viewportBefore = compose.onNodeWithTag("remote-history-content").fetchSemanticsNode().boundsInRoot
        assertTrue("The entire measured 48dp pointer square must be inside the native history viewport",
            pointer.positionInRoot.y - extension >= viewportBefore.top &&
                pointer.positionInRoot.y + pointer.size.height + extension <= viewportBefore.bottom &&
                centerX - 24f * density >= 0f && centerX + 24f * density <= pointer.size.width)
        for (x in listOf(centerX - 24f * density, centerX + 24f * density)) {
            for (y in listOf(-extension, pointer.size.height + extension)) {
                slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
                slider.performTouchInput { click(Offset(x, y)) }
                assertTrue("A native pointer at the 48dp square boundary must select the actual earlier sample",
                    slider.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].any { it.contains("3.7 V") })
            }
        }
        println("WP503_REMOTE_HISTORY_POINTER|semanticHeight=${pointer.size.height / density}|extension=${extension / density}|verifiedSquare=48x48dp|corners=4")
        val value = compose.onNodeWithText("3.7 V", substring = true).performScrollTo().assertIsDisplayed()
        assertFont200Unclipped(value)
        val node = value.fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("remote-history-content").fetchSemanticsNode().boundsInRoot
        assertTrue("The actual numeric alternative must be fully contained after native scroll",
            node.boundsInRoot.width == node.size.width.toFloat() && node.boundsInRoot.height == node.size.height.toFloat() &&
                node.boundsInRoot.left >= viewport.left && node.boundsInRoot.right <= viewport.right &&
                node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom)
        assertEquals(1, rig.historyReads)
        compose.onNodeWithText(host.get().getString(L.remoteNodesDone)).performClick()
        compose.onNodeWithTag("remote-node-list").assertIsDisplayed()
    }

    @Test fun frameworkSliderCalibratesNativeSemanticsVersusPointerTargetExpansion() {
        val value = mutableFloatStateOf(.5f)
        var callbacks = 0
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column(Modifier.padding(64.dp)) {
                        Slider(value.floatValue, { value.floatValue = it; callbacks++ },
                            modifier = Modifier.testTag("framework-slider"))
                    }
                }
            }
        }
        val slider = compose.onNodeWithTag("framework-slider").assertIsDisplayed()
        val measured = slider.fetchSemanticsNode()
        slider.performTouchInput { click(Offset(measured.size.width * .8f, measured.size.height / 2f)) }
        assertTrue("The framework control must receive an actual interior pointer event", callbacks > 0)
        compose.runOnIdle { value.floatValue = .5f; callbacks = 0 }
        slider.performTouchInput { click(Offset(measured.size.width * .8f, -2f)) }
        val top = callbacks
        compose.runOnIdle { value.floatValue = .5f; callbacks = 0 }
        slider.performTouchInput { click(Offset(measured.size.width * .8f, measured.size.height + 2f)) }
        println("WP503_FRAMEWORK_SLIDER|bounds=${measured.touchBoundsInRoot}|size=${measured.size}|top2pxCallbacks=$top|bottom2pxCallbacks=$callbacks")
        assertEquals("Native pointer expansion, not the 44px semantic box, must be calibrated", 1, top)
        assertEquals(1, callbacks)
    }

    @Test fun emptyOfflineHistoryExplainsTheAbsenceOfSnapshotsWithoutDrawingAFakeChart() {
        val rig = Rig().apply { empty = true }
        mount(rig)
        openHistory()
        val empty = compose.onNodeWithText(host.get().getString(L.remoteNodesHistoryNoSnapshotsMessage))
            .performScrollTo().assertIsDisplayed()
        assertFont200Unclipped(empty)
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().size)
        assertEquals(1, rig.historyReads)
    }

    private fun mount(rig: Rig) {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    RemoteNodesEntry(FeatureRoute(FeatureId.REMOTE_NODES), { error("Unexpected cross-feature navigation") }, rig)
                }
            }
        }
    }

    private fun openHistory() {
        compose.onNodeWithText(host.get().getString(L.remoteNodesHistoryOverviewTitle)).performScrollTo().performClick()
        compose.onNodeWithTag("remote-history-content").assertIsDisplayed()
    }

    private inner class Rig : RemoteNodesUiDependencies {
        var catalogFailure = false
        var empty = false
        var catalogReads = 0
        var historyReads = 0
        override val updates = MutableStateFlow(RemoteNodesConnection(radio, null, false))
        override val radioOptions = RemoteRadioOptions(listOf(125.0), listOf(7L), listOf(5L))
        override suspend fun catalog(): RemoteNodesCatalog {
            catalogReads++
            if (catalogFailure) throw IOException("Deterministic catalog read failure")
            return RemoteNodesCatalog(listOf(contact), emptyList(), emptyList())
        }
        private val store = object : RemoteNodeHistoryStore {
            override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?) =
                (if (empty) emptyList() else listOf(
                    NodeStatusSnapshotDTO(id = UUID(0, 103), timestamp = Instant.ofEpochSecond(1_700_000_000),
                        nodePublicKey = contact.publicKey, batteryMillivolts = 3700u),
                    NodeStatusSnapshotDTO(id = UUID(0, 104), timestamp = Instant.ofEpochSecond(1_700_000_300),
                        nodePublicKey = contact.publicKey, batteryMillivolts = 3900u),
                )).also {
                    assertEquals(contact.publicKey, nodePublicKey)
                    assertEquals(null, since)
                    historyReads++
                }.snapshot()
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO {
                assertEquals(radio, radioId)
                assertEquals(contact.publicKey, publicKey)
                return contact
            }
            override suspend fun fetchContacts(radioId: RadioId) =
                listOf(contact).also { assertEquals(radio, radioId) }.snapshot()
            override suspend fun fetchDiscoveredNodes(radioId: RadioId) =
                emptyList<com.meshcoreone.android.core.model.DiscoveredNodeDTO>()
                    .also { assertEquals(radio, radioId) }.snapshot()
        }
        private val offline = object : RemoteNodesFeatureDependencies {
            override val clock = object : RemoteNodesClock {
                override val now = Instant.ofEpochSecond(1_700_000_600)
                override val elapsed = Duration.ZERO
                override suspend fun sleep(duration: Duration) = error("Read-only offline flow must not poll or retry transport")
            }
            override val faults = object : RemoteNodeFaultClassifier {
                override fun isTimeout(error: Throwable): Boolean = error("Unexpected service fault")
                override fun isRemoteNoResponseYet(error: Throwable): Boolean = error("Unexpected service fault")
                override fun isBinarySessionTimeout(error: Throwable): Boolean = error("Unexpected service fault")
            }
            override fun repeaterAdmin(): RepeaterAdminPort? = null
            override fun roomAdmin(): RoomAdminPort? = null
            override fun binaryTelemetry(): BinaryTelemetryPort? = null
            override fun nodeSnapshots(): NodeSnapshotPort? = null
            override fun contactOcv(): ContactOcvPort? = null
            override fun historyStore() = store
            override fun login(): RemoteNodeLoginPort? = null
            override fun connectedRadioId(): RadioId? = null
            override fun deviceHashSize(): Int? = null
        }
        override fun services() = offline
    }
}
