// AndroidOnly: WP-503 Public remote-node catalog/history/authentication/status flows with deterministic ports and actual native scroll, controls and cancellation.
package com.meshcoreone.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesClock
import com.meshcoreone.android.feature.remotenodes.dependencies.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription

@RunWith(AndroidJUnit4::class)
class NativeRemoteNodesAccessibilityTest : DeviceAccessibilityHarness() {
    private val radio = RadioId(UUID(0, 101))
    private val contact = ContactDTO(id = UUID(0, 102), radioId = radio,
        publicKey = Bytes(ByteArray(32) { 0x33 }), name = "\u4e2d\u6587 remote",
        typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null)

    @Test fun nativeRegionsKeepNamedSwitchesAndCappedRtlGuttersAtFont200() {
        val rig = Rig().apply { regionAdmin = true; ready() }
        openRegions(rig)
        val labels = regionLabels()
        assertEquals(6, labels.toSet().size)
        assertEquals(6, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .fetchSemanticsNodes().size)
        val expectedGutters = listOf(0f, 24f, 48f, 48f, 48f, 48f)
        var rootRight = 0f
        for ((index, label) in labels.withIndex()) {
            val action = regionSwitch(label).performScrollTo().assertIsDisplayed().assertIsEnabled().assertIsOn()
            val node = action.fetchSemanticsNode()
            if (index == 0) rootRight = node.touchBoundsInRoot.right
            val gutter = (rootRight - node.touchBoundsInRoot.right) / activity.resources.displayMetrics.density
            assertEquals("Source-derived font200 RTL gutter for $label", expectedGutters[index], gutter, 0.5f)
            val hint = activity.getString(L.remoteNodesSettingsRegionsFloodToggleHint)
            assertEquals(hint, node.config[SemanticsActions.OnClick].label)
            val caption = compose.onNode(
                hasText(activity.getString(L.remoteNodesSettingsRegionsFloodToggleCaption)) and
                    hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true,
            )
            assertFont200Unclipped(caption.assertIsDisplayed())
            val native = nativeRegionSwitch(label)
            assertTrue(native.isEnabled && native.isChecked)
            val roles = nativeRegionDescendants(native).map {
                "${it.className}:${AccessibilityNodeInfoCompat.wrap(it).roleDescription}"
            }.toList()
            println("WP503_NATIVE_REGION_ROLES|label=$label|roles=$roles")
            assertTrue("The named native action must retain its Switch role",
                nativeRegionDescendants(native).any {
                    AccessibilityNodeInfoCompat.wrap(it).roleDescription?.toString() == "Switch"
                })
            assertEquals(hint, native.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label?.toString())
            val bounds = Rect().also(native::getBoundsInScreen)
            val root = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            val viewport = Rect().also(root::getBoundsInScreen)
            val density = activity.resources.displayMetrics.density
            assertTrue("Each actual native region switch must be wholly contained and48dp",
                viewport.contains(bounds) && bounds.width() / density >= 48f && bounds.height() / density >= 48f)
            println("WP503_NATIVE_REGION|sdk=${android.os.Build.VERSION.SDK_INT}|label=$label|gutter=$gutter" +
                "|expectedGutter=${expectedGutters[index]}|bounds=$bounds|viewport=$viewport|checked=true" +
                "|hint=$hint|fontScale=2.0|physicalTalkBack=false|realRadio=false")
        }
        assertEquals(listOf("region"), rig.regionCommands)
        assertEquals(1, rig.ownerInfoReads)
    }

    @Test fun nativeRegionFloodRequiresConfirmationAndChangesOnlyTheNamedRegion() {
        val rig = Rig().apply { regionAdmin = true; ready() }
        openRegions(rig)
        val labels = regionLabels()
        val target = regionSwitch(labels[2]).performScrollTo().assertIsOn()
        target.performClick()
        assertEquals(listOf("region"), rig.regionCommands)
        val cancel = compose.onNode(hasText(activity.getString(L.remoteNodesCancel)) and
            hasClickAction() and hasAnyAncestor(isDialog()))
        assertModalTarget(cancel)
        cancel.performClick()
        regionSwitch(labels[2]).assertIsOn().performClick()
        val confirm = compose.onNodeWithTag("confirm-remote-action")
        assertModalTarget(confirm)
        assertFont200Unclipped(compose.onNode(
            hasText(activity.getString(L.remoteNodesSettingsOk)) and hasAnyAncestor(isDialog()),
            useUnmergedTree = true,
        ))
        assertEquals(listOf("region"), rig.regionCommands)
        confirm.performClick()
        compose.waitUntil { rig.regionCommands.size == 2 }
        assertEquals(listOf("region", "region denyf gta"), rig.regionCommands)
        for ((index, label) in labels.withIndex()) {
            val switch = regionSwitch(label).performScrollTo().assertIsDisplayed().assertIsEnabled()
            if (index == 2) switch.assertIsOff() else switch.assertIsOn()
        }
        println("WP503_NATIVE_REGION_CONFIRM|sdk=${android.os.Build.VERSION.SDK_INT}" +
            "|cancelWrites=0|confirmedCommand=region denyf gta|otherRegionsUnchanged=5|realRadio=false")
    }

    private fun regionLabels(): List<String> {
        val root = activity.getString(L.remoteNodesSettingsRegionsAllTraffic)
        return listOf(root,
            activity.getString(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "on", root),
            activity.getString(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "gta", "on"),
            activity.getString(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "downtown", "gta"),
            activity.getString(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "east", "downtown"),
            activity.getString(R.string.l10n_app_remotenodes_remotenodes_settings_regions_childof, "north", "east"))
    }

    private fun regionSwitch(label: String) = compose.onNode(hasContentDescription(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    private fun openRegions(rig: Rig) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
        mount(rig)
        compose.onNodeWithTag("open-node:${contact.id}").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("remote-tab:SETTINGS").assertIsSelected()
        compose.onNode(hasText(activity.getString(L.remoteNodesSettingsRegions)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo().performClick()
        compose.waitUntil { rig.regionCommands == listOf("region") }
        regionSwitch(regionLabels().first()).assertExists()
    }

    private fun nativeRegionSwitch(label: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var matches = emptyList<AccessibilityNodeInfo>()
        var observed = emptyList<AccessibilityNodeInfo>()
        try {
            compose.waitUntil {
                val root = automation.rootInActiveWindow
                if (root?.packageName?.toString() != activity.packageName) return@waitUntil false
                observed = nativeRegionDescendants(root).toList()
                matches = observed.filter { it.contentDescription?.toString() == label }.mapNotNull { named ->
                    generateSequence(named) { it.parent }.onEach {
                        assertTrue("Refresh the named region's actual action ancestry", it.refresh())
                    }.firstOrNull { it.isCheckable }
                }.distinct()
                matches.size == 1
            }
        } finally {
            if (matches.size != 1) observed.forEach {
                println("WP503_NATIVE_REGION_TREE|expected=$label|class=${it.className}|text=${it.text}" +
                    "|description=${it.contentDescription}|checkable=${it.isCheckable}|checked=${it.isChecked}" +
                    "|role=${AccessibilityNodeInfoCompat.wrap(it).roleDescription}|actions=${it.actionList}")
            }
        }
        assertEquals("Exactly one native named region switch", 1, matches.size)
        return matches.single()
    }

    private fun nativeRegionDescendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        assertTrue("Refresh actual region bridge data", node.refresh())
        yield(node)
        for (index in 0 until node.childCount) yieldAll(nativeRegionDescendants(requireNotNull(node.getChild(index))))
    }

    @Test fun guestStatusAndEmptyTelemetryExposeRealUnitsRecoveryAndHandlerTeardownAtFont200() {
        val rig = Rig().apply { savedGuest = true; ready() }
        mount(rig)
        compose.onNodeWithTag("open-node:${contact.id}").performClick()
        compose.onNodeWithTag("remote-management").assertIsDisplayed()
        compose.onNodeWithText(activity.getString(L.remoteNodesPermissionGuest)).assertIsDisplayed()
        assertFont200Unclipped(compose.onNodeWithText(activity.getString(L.remoteNodesStatusGuestMode)))
        compose.onNodeWithTag("remote-tab:SETTINGS").assertDoesNotExist()
        compose.onNodeWithTag("remote-tab:CLI").assertDoesNotExist()
        val status = compose.onNode(hasText(activity.getString(L.remoteNodesStatusTitle)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo()
        status.performClick()
        compose.waitUntil { rig.statusReads == 1 }
        for (value in listOf("3.700V", "-90 dBm", "5.0 dB", "-110 dBm")) {
            assertScrollableFont200Unclipped(compose.onNodeWithText(value, substring = true))
        }
        status.performScrollTo().performClick()
        val telemetry = compose.onNode(hasText(activity.getString(L.remoteNodesStatusTelemetry)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo()
        telemetry.performClick()
        compose.waitUntil { rig.telemetryReads == 1 }
        assertScrollableFont200Unclipped(compose.onNodeWithText(activity.getString(L.remoteNodesStatusNoSensorData)))
        assertScrollableFont200Unclipped(compose.onNodeWithText(activity.getString(L.remoteNodesStatusNoTelemetryData)))
        compose.onNodeWithText(activity.getString(L.remoteNodesStatusViewOnMap)).assertDoesNotExist()
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().size)
        compose.runOnIdle { rig.telemetryFailure = true }
        val reload = compose.onNodeWithTag("reload:${L.remoteNodesStatusTelemetry}")
        reload.performScrollTo().performClick()
        val error = compose.onNodeWithTag("remote-error").performScrollTo().assertIsDisplayed()
        assertScrollableFont200Unclipped(error)
        assertTrue(error.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals(2, rig.telemetryReads)
        compose.runOnIdle { rig.telemetryFailure = false }
        reload.performScrollTo().performClick()
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        assertEquals(3, rig.telemetryReads)
        assertVisibleClickTargetsAtLeast48Dp()
        compose.onNodeWithText(activity.getString(L.remoteNodesDone)).performClick()
        compose.onNodeWithTag("remote-node-list").assertIsDisplayed()
        compose.waitUntil { rig.statusTeardowns == 1 }
        assertEquals(3, rig.handlerRegistrations)
        assertEquals(1, rig.statusReads)
        assertEquals(0, rig.passwordReads)
        assertEquals(0, rig.loginAttempts)
        println("WP503_REMOTE_GUEST_STATUS|statusReads=1|telemetryReads=3|registered=3|teardown=1|realRadio=false")
    }

    @Test fun populatedTelemetryKeepsChannelUnitsCoordinatesAndNativeMapRecoveryReachableAtFont200() {
        assertEquals("This independent Fahrenheit oracle requires the actual US device locale",
            "US", Locale.getDefault().country)
        val rig = Rig().apply {
            savedGuest = true
            // LPP: channel1 25.5C/22.5C/3.85V/GPS(37.7749,-122.4194,42m), channel2 55.5%.
            telemetryRaw = Bytes.fromHex("016700ff016700e101740181018805c395ed51fe00106802686f")
            ready()
        }
        mount(rig)
        compose.onNodeWithTag("open-node:${contact.id}").performClick()
        val telemetry = compose.onNode(hasText(activity.getString(L.remoteNodesStatusTelemetry)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo()
        telemetry.performClick()
        compose.waitUntil { rig.telemetryReads == 1 }
        for (channel in listOf(1, 2)) {
            val heading = compose.onNode(hasText(L.remoteNodesStatusChannel(activity.resources, channel)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            assertScrollableFont200Unclipped(heading)
        }
        val channelOne = compose.onNode(hasText(L.remoteNodesStatusChannel(activity.resources, 1)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().id
        val channelTwo = compose.onNode(hasText(L.remoteNodesStatusChannel(activity.resources, 2)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().id
        val headingOrder = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNodes().map { it.id }
        assertTrue("Actual semantic traversal retains ascending channel headings",
            headingOrder.indexOf(channelOne) >= 0 && headingOrder.indexOf(channelTwo) > headingOrder.indexOf(channelOne))
        for (label in listOf(L.remoteNodesStatusSensorTemperature, L.remoteNodesStatusSensorMcuTemperature,
            L.remoteNodesStatusSensorVoltage, L.remoteNodesStatusSensorGps, L.remoteNodesStatusSensorHumidity)) {
            assertScrollableFont200Unclipped(compose.onNodeWithText(activity.getString(label)))
        }
        for (value in listOf("77.9 \u00b0F", "72.5 \u00b0F", "3.850 V",
            "37.77490, -122.41940 @ 42.0m")) {
            assertScrollableFont200Unclipped(compose.onNodeWithText(value))
        }
        assertFont200PaintedUnclipped(compose.onNodeWithText("55.5%").performScrollTo())
        compose.onNodeWithText(activity.getString(L.remoteNodesStatusNoSensorData)).assertDoesNotExist()
        compose.onNodeWithText(activity.getString(L.remoteNodesStatusNoTelemetryData)).assertDoesNotExist()
        val viewMap = compose.onNodeWithText(activity.getString(L.remoteNodesStatusViewOnMap)).performScrollTo()
        assertFont200Unclipped(viewMap)
        assertVisibleClickTargetsAtLeast48Dp()
        viewMap.performClick()
        val mapTitle = activity.getString(L.remoteNodesStatusLocationMapTitle)
        val mapHeading = compose.onNode(hasText(mapTitle) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertScrollableFont200Unclipped(mapHeading)
        onView(withContentDescription(mapTitle)).check(matches(isDisplayed()))
        val satellite = compose.onNodeWithText(activity.getString(M.mapStyleSatellite))
        satellite.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        assertScrollableFont200Unclipped(compose.onNodeWithText("OpenFreeMap does not provide satellite imagery."))
        onView(withContentDescription(mapTitle)).check(doesNotExist())
        val standard = compose.onNodeWithText(activity.getString(M.mapStyleStandard))
        standard.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        mapHeading.performScrollTo()
        onView(withContentDescription(mapTitle)).check(matches(isDisplayed()))
        val labels = compose.onNodeWithText(activity.getString(M.mapControlsShowLabels)).performScrollTo()
        labels.assertIsOn().performClick().assertIsOff().performClick().assertIsOn()
        val clusters = compose.onNodeWithText(activity.getString(M.mapControlsClusterNodes)).performScrollTo()
        clusters.assertIsOn().performClick().assertIsOff().performClick().assertIsOn()
        assertVisibleClickTargetsAtLeast48Dp()
        compose.onNode(hasText(activity.getString(L.remoteNodesDone)) and
            hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)))
            .performScrollTo().performClick()
        assertScrollableFont200Unclipped(compose.onNodeWithText("37.77490, -122.41940 @ 42.0m"))
        compose.onNodeWithText(activity.getString(L.remoteNodesDone)).performClick()
        compose.onNodeWithTag("remote-node-list").assertIsDisplayed()
        compose.waitUntil { rig.statusTeardowns == 1 }
        assertEquals(1, rig.telemetryReads)
        assertEquals(0, rig.statusReads)
        assertEquals(3, rig.handlerRegistrations)
        assertEquals(0, rig.loginAttempts)
        println("WP503_REMOTE_POPULATED_TELEMETRY|channels=2|points=5|reads=1|nativeMap=true|styleRecovery=true|realRadio=false")
    }

    @Test fun pendingGuestTelemetryDisablesDuplicateReadsAndCancelsOnActualDoneAtFont200() {
        val rig = Rig().apply { savedGuest = true; pendingTelemetry = true; ready() }
        mount(rig)
        compose.onNodeWithTag("open-node:${contact.id}").performClick()
        val telemetry = compose.onNode(hasText(activity.getString(L.remoteNodesStatusTelemetry)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo()
        assertFont200Unclipped(telemetry)
        telemetry.performClick()
        compose.waitUntil { rig.telemetryReads == 1 }
        compose.onNodeWithTag("reload:${L.remoteNodesStatusTelemetry}").assertIsNotEnabled()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        assertVisibleClickTargetsAtLeast48Dp()
        compose.onNodeWithText(activity.getString(L.remoteNodesDone)).performClick()
        compose.onNodeWithTag("remote-node-list").assertIsDisplayed()
        compose.waitUntil { rig.cancelledTelemetry == 1 && rig.statusTeardowns == 1 }
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        assertEquals(1, rig.telemetryReads)
        assertEquals(3, rig.handlerRegistrations)
        assertEquals(0, rig.loginAttempts)
        println("WP503_REMOTE_PENDING_TELEMETRY|reads=1|cancelled=1|teardown=1|duplicateEnabled=false|realRadio=false")
    }

    @Test fun frameworkNeutralRtlParagraphRetainsAllNativeInkAndRejectsActualAncestorClipping() {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                        Text("55.5%", Modifier.testTag("neutral-rtl"),
                            style = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Right))
                        Box(Modifier.size(50.dp, 60.dp).clipToBounds().testTag("neutral-rtl-viewport")) {
                            Text("55.5%", Modifier.requiredWidth(76.dp).testTag("neutral-rtl-clipped"),
                                style = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Right))
                        }
                    }
                }
            }
        }
        val visible = compose.onNodeWithTag("neutral-rtl")
        val layout = font200Layout(visible)
        assertTrue("Neutral RTL line coordinates retain the parent's paragraph allocation",
            layout.getLineRight(0) > layout.size.width && layout.getLineLeft(0) > 0f)
        assertFont200PaintedUnclipped(visible)
        val clipped = compose.onNodeWithTag("neutral-rtl-clipped")
        assertEquals(50, compose.onNodeWithTag("neutral-rtl-viewport").fetchSemanticsNode().size.width)
        assertTrue("The negative control loses real ink at its ancestor, not maxLines",
            !textLayout(clipped).multiParagraph.didExceedMaxLines)
        val failure = assertThrows(AssertionError::class.java) { assertFont200PaintedUnclipped(clipped) }
        assertTrue(failure.message.orEmpty().contains("retain"))
        println("WP503_NEUTRAL_RTL_FONT_CONTROL|fullInk=true|actualAncestorClipRejected=true|tolerance=0")
    }

    @Test fun catalogFailureIsTextualAndRefreshRecoversTheActualOfflineRemoteRow() {
        val rig = Rig().apply { catalogFailure = true }
        mount(rig)
        val failure = compose.onNodeWithTag("remote-error").assertIsDisplayed()
        assertFont200Unclipped(failure)
        assertTrue(failure.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals(1, rig.catalogReads)
        compose.runOnIdle { rig.catalogFailure = false }
        compose.onNodeWithText(activity.getString(L.remoteNodesStatusRefresh)).performClick()
        compose.onNode(hasText(contact.displayName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed()
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        compose.onNodeWithText(activity.getString(L.remoteNodesRoomDisconnectedBanner)).assertIsDisplayed()
        assertVisibleClickTargetsAtLeast48Dp()
        assertEquals(2, rig.catalogReads)
    }

    @Test fun offlineSavedHistoryExposesActualVoltageAndNonGestureSampleSelectionAtFont200() {
        val rig = Rig()
        mount(rig)
        openHistory()
        val title = activity.getString(L.remoteNodesHistoryBattery)
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            hasContentDescription(title, substring = true)).performScrollTo().assertIsDisplayed()
        val measured = slider.fetchSemanticsNode()
        val density = activity.resources.displayMetrics.density
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
        compose.onNodeWithText(activity.getString(L.remoteNodesDone)).performClick()
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
        val empty = compose.onNodeWithText(activity.getString(L.remoteNodesHistoryNoSnapshotsMessage))
            .performScrollTo().assertIsDisplayed()
        assertFont200Unclipped(empty)
        assertEquals(0, compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().size)
        assertEquals(1, rig.historyReads)
    }

    @Test fun authenticationPrefillsProtectedPasswordAndExposesLabelledRememberAndFloodState() {
        val rig = Rig().apply { ready() }
        mount(rig)
        openAuthentication(rig)
        val password = compose.onNodeWithTag("remote-login-password").performScrollTo().assertIsEnabled()
        assertTrue(password.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        assertEquals("\u2022".repeat(12), password.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val remember = rememberToggle().performScrollTo().assertIsOn().assertIsEnabled()
        assertModalTarget(remember)
        assertScrollableFont200Unclipped(compose.onNodeWithText(activity.getString(L.remoteNodesAuthRememberPassword),
            useUnmergedTree = true))
        remember.performClick().assertIsOff()
        val flood = compose.onNode(hasText(activity.getString(L.remoteNodesAuthFloodRouting)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performScrollTo()
        flood.assertIsOn().assertIsNotEnabled()
        assertScrollableFont200Unclipped(compose.onNodeWithText(activity.getString(L.remoteNodesAuthNoRouteFooter),
            useUnmergedTree = true))
        assertModalTarget(compose.onNodeWithTag("remote-login").assertIsEnabled())
        cancelAuthentication()
        assertEquals(1, rig.passwordReads)
        assertEquals(0, rig.loginAttempts)
        assertEquals(0, rig.passwordDeletes)
    }

    @Test fun authenticationLongPasswordWarningAndTypedFailureRemainReachableAndEditingRecovers() {
        val rig = Rig().apply { ready(); authenticationFailure = true; expectedPassword = "0123456789ABCDE" }
        mount(rig)
        openAuthentication(rig)
        val password = compose.onNodeWithTag("remote-login-password").performScrollTo()
        password.performTextReplacement("0123456789ABCDEextra")
        assertScrollableFont200Unclipped(compose.onNodeWithText(
            L.remoteNodesAuthPasswordTooLongRepeaters(activity.resources, 15), useUnmergedTree = true))
        compose.onNodeWithTag("remote-login").performClick()
        val failure = compose.onNodeWithTag("remote-error").performScrollTo().assertIsDisplayed()
        assertScrollableFont200Unclipped(failure)
        assertTrue(failure.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
        assertEquals(1, rig.loginAttempts)
        password.performScrollTo().performTextReplacement("edited-audit")
        compose.onNodeWithTag("remote-error").assertDoesNotExist()
        compose.onNodeWithTag("remote-login").assertIsEnabled()
        cancelAuthentication()
        assertEquals(0, rig.passwordDeletes)
    }

    @Test fun pendingAuthenticationHasTextualCountdownDisabledDuplicatesAndRealCancellation() {
        val rig = Rig().apply { ready(); pendingAuthentication = true; expectedRemember = false }
        mount(rig)
        openAuthentication(rig)
        rememberToggle().performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("remote-login").performClick()
        compose.waitUntil { rig.loginAttempts == 1 }
        compose.onNodeWithTag("remote-login").assertIsNotEnabled()
        compose.onNodeWithTag("remote-login-password").assertIsNotEnabled()
        rememberToggle().assertIsNotEnabled()
        assertScrollableFont200Unclipped(compose.onNodeWithText(
            L.remoteNodesAuthSecondsRemaining(activity.resources, 30), useUnmergedTree = true))
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            hasAnyAncestor(isDialog()))
        var measured = progress.fetchSemanticsNode()
        val scroll = generateSequence(measured.parent) { it.parent }.first {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        val viewport = scroll.boundsInRoot
        val before = measured.layoutInfo.coordinates.boundsInRoot()
        val origin = measured.layoutInfo.coordinates.positionInRoot()
        val bottom = origin.y + measured.layoutInfo.height
        val delta = if (bottom > viewport.bottom) bottom - viewport.bottom
            else if (origin.y < viewport.top) origin.y - viewport.top else 0f
        if (delta != 0f) {
            compose.onNode(SemanticsMatcher("Actual progress scroll viewport") { it.id == scroll.id },
                useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) {
                assertTrue("The actual visual bar must be reachable by non-gesture scroll", it(0f, delta))
            }
        }
        measured = progress.fetchSemanticsNode()
        val layout = measured.layoutInfo
        val visual = layout.coordinates.boundsInRoot()
        println("WP503_REMOTE_PENDING_PROGRESS|semantics=${measured.size}|allocation=${layout.width}x${layout.height}|before=$before|scroll=$delta|visual=$visual|viewport=$viewport")
        progress.assertIsDisplayed()
        assertTrue("The actual visual progress allocation, not expanded noninteractive semantics, must be wholly contained",
            layout.width > 0 && layout.height > 0 &&
                visual.width == layout.width.toFloat() && visual.height == layout.height.toFloat() &&
                visual.left >= viewport.left && visual.right <= viewport.right &&
                visual.top >= viewport.top && visual.bottom <= viewport.bottom)
        cancelAuthentication()
        compose.waitUntil { rig.cancelledAuthentications == 1 }
        assertEquals(1, rig.loginAttempts)
        assertEquals(0, rig.passwordDeletes)
    }

    private fun openAuthentication(rig: Rig) {
        compose.onNodeWithTag("open-node:${contact.id}").performScrollTo().assertIsEnabled().performClick()
        val password = compose.onNodeWithTag("remote-login-password").assertIsDisplayed()
        compose.waitUntil(10_000) {
            password.fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "\u2022".repeat(12)
        }
        assertEquals(1, rig.passwordReads)
    }

    @Test fun frameworkSecureDialogPrefillIsMaskedAndIdleAtSystemFont200() {
        val password = mutableStateOf("")
        var placements = 0
        val geometry = linkedSetOf<String>()
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    LaunchedEffect(Unit) { password.value = "cached-audit" }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Secure field control") },
                        text = {
                            OutlinedTextField(password.value, { password.value = it },
                                modifier = Modifier.testTag("secure-field-control").onGloballyPositioned {
                                    placements++
                                    val sample = "${it.size}|${it.boundsInRoot()}"
                                    if (geometry.size < 16 && geometry.add(sample)) println("WP503_SECURE_GEOMETRY|$sample")
                                },
                                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                        },
                        confirmButton = { TextButton({}) { Text("Control") } },
                    )
                }
            }
        }
        try {
            val field = compose.onNodeWithTag("secure-field-control").assertIsDisplayed()
            assertEquals("\u2022".repeat(12), field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        } finally {
            println("WP503_SECURE_PLACEMENTS|count=$placements|distinct=${geometry.size}")
        }
    }

    @Test fun frameworkLinearProgressCalibratesExpandedSemanticsVersusActualNativeAllocation() {
        var allocation = IntSize.Zero
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column(Modifier.padding(24.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().onSizeChanged { allocation = it }
                            .testTag("framework-progress-control"))
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("framework-progress-control").assertIsDisplayed().fetchSemanticsNode()
        val density = activity.resources.displayMetrics.density
        val visual = node.layoutInfo.coordinates.boundsInRoot()
        println("WP503_FRAMEWORK_PROGRESS|semantics=${node.size}|allocation=$allocation|layout=${node.layoutInfo.width}x${node.layoutInfo.height}|visual=$visual")
        assertEquals(4f, allocation.height / density, 0f)
        assertEquals(allocation.height, node.layoutInfo.height)
        assertEquals(24f, node.size.height / density, 0f)
        assertEquals(allocation.height.toFloat(), visual.height, 0f)
    }

    private fun rememberToggle() = compose.onNode(hasText(activity.getString(L.remoteNodesAuthRememberPassword)) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    private fun assertModalTarget(interaction: SemanticsNodeInteraction) {
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        assertTrue(generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.IsDialog) })
        val viewport = generateSequence(node) { it.parent }.last().boundsInRoot
        val bounds = node.touchBoundsInRoot
        val density = activity.resources.displayMetrics.density
        println("WP503_REMOTE_AUTH_TARGET|bounds=$bounds|viewport=$viewport")
        assertTrue("The actual enabled modal control must retain a complete 48dp target",
            bounds.width / density >= 48f && bounds.height / density >= 48f &&
                bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
    }

    private fun cancelAuthentication() {
        val cancel = compose.onNodeWithText(activity.getString(L.remoteNodesCancel)).assertIsEnabled()
        assertModalTarget(cancel)
        cancel.performClick()
        compose.onNodeWithTag("remote-login-password").assertDoesNotExist()
        compose.onNodeWithTag("remote-node-list").assertIsDisplayed()
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
        compose.onNodeWithText(activity.getString(L.remoteNodesHistoryOverviewTitle)).performScrollTo().performClick()
        compose.onNodeWithTag("remote-history-content").assertIsDisplayed()
    }

    private inner class Rig : RemoteNodesUiDependencies {
        var regionAdmin = false
        val regionCommands = mutableListOf<String>()
        var ownerInfoReads = 0
        var catalogFailure = false
        var empty = false
        var catalogReads = 0
        var historyReads = 0
        var passwordReads = 0
        var passwordDeletes = 0
        var loginAttempts = 0
        var cancelledAuthentications = 0
        var authenticationFailure = false
        var pendingAuthentication = false
        var savedGuest = false
        var telemetryFailure = false
        var telemetryRaw = Bytes.EMPTY
        var pendingTelemetry = false
        var cancelledTelemetry = 0
        var statusReads = 0
        var telemetryReads = 0
        var handlerRegistrations = 0
        var statusTeardowns = 0
        var expectedPassword = "cached-audit"
        var expectedRemember = true
        override val updates = MutableStateFlow(RemoteNodesConnection(radio, null, false))
        fun ready() { updates.value = RemoteNodesConnection(radio, "audit-generation", true) }
        override val radioOptions = RemoteRadioOptions(listOf(125.0), listOf(7L), listOf(5L))
        override suspend fun catalog(): RemoteNodesCatalog {
            catalogReads++
            if (catalogFailure) throw IOException("Deterministic catalog read failure")
            return RemoteNodesCatalog(listOf(contact), emptyList(), when {
                regionAdmin -> listOf(guestSession.copy(permissionLevel = RoomPermissionLevel.ADMIN))
                savedGuest -> listOf(guestSession)
                else -> emptyList()
            })
        }
        private val guestSession = RemoteNodeSessionDTO(
            id = UUID(0, 105), radioId = radio, publicKey = contact.publicKey, name = contact.displayName,
            role = RemoteNodeRole.REPEATER, permissionLevel = RoomPermissionLevel.GUEST, isConnected = true,
        )
        private val statusPort = object : RepeaterAdminPort {
            override suspend fun requestStatus(session: EntityKey, timeout: Duration?): StatusResponse {
                assertEquals(EntityKey(radio, guestSession.id), session)
                assertTrue(requireNotNull(timeout) > Duration.ZERO)
                statusReads++
                return StatusResponse(
                    publicKeyPrefix = contact.publicKey.prefix(6), battery = 3700, txQueueLength = 0,
                    noiseFloor = -110, lastRSSI = -90, packetsReceived = 22u, packetsSent = 11u,
                    airtime = 60u, uptime = 3600u, sentFlood = 1u, sentDirect = 10u,
                    receivedFlood = 2u, receivedDirect = 20u, fullEvents = 0, lastSNR = 5.0,
                    directDuplicates = 1, floodDuplicates = 2, rxAirtime = 120u,
                )
            }
            override suspend fun requestTelemetry(session: EntityKey, timeout: Duration?): TelemetryResponse {
                assertEquals(EntityKey(radio, guestSession.id), session)
                assertTrue(requireNotNull(timeout) > Duration.ZERO)
                telemetryReads++
                if (pendingTelemetry) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledTelemetry++
                    }
                }
                if (telemetryFailure) throw IOException("Deterministic guest telemetry read failure")
                return TelemetryResponse(contact.publicKey.prefix(6), null, telemetryRaw)
            }
            override fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) { handlerRegistrations++ }
            override fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) { handlerRegistrations++ }
            override fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) { handlerRegistrations++ }
            override fun clearStatusHandlers() { statusTeardowns++ }
            override fun clearHandlers() = error("Status teardown must not clear the shared CLI handler")
            override fun setCLIHandler(handler: suspend (ContactMessage, ContactDTO) -> Unit) {
                assertTrue("Only the explicit region admin fixture may register CLI", regionAdmin)
            }
            override suspend fun sendCommand(session: EntityKey, command: String, timeout: Duration): String =
                regionCommand(session, command)
            override suspend fun sendRawCommand(session: EntityKey, command: String, timeout: Duration): String =
                regionCommand(session, command)
            override suspend fun requestOwnerInfo(session: EntityKey, timeout: Duration?): OwnerInfoResponse {
                assertTrue("Only the explicit region admin fixture requests owner information", regionAdmin)
                assertEquals(EntityKey(radio, guestSession.id), session)
                ownerInfoReads++
                return OwnerInfoResponse("v1.14.0", contact.name, "Synthetic region fixture")
            }
            override suspend fun fetchAllNeighbors(session: EntityKey, timeout: Duration?): NeighboursResponse =
                error("No neighbors request made")
        }
        private fun regionCommand(session: EntityKey, command: String): String {
            assertTrue("Guest fixtures must never issue CLI commands", regionAdmin)
            assertEquals(EntityKey(radio, guestSession.id), session)
            regionCommands += command
            return when (command) {
                "region" -> listOf("*", "on", "gta", "downtown", "east", "north").mapIndexed { depth, name ->
                    "${" ".repeat(depth)}$name F"
                }.joinToString("\n", postfix = "\n")
                "region denyf gta" -> "OK"
                else -> error("Unexpected deterministic region command: $command")
            }
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
                override suspend fun sleep(duration: Duration): Nothing {
                    assertTrue("Only the real pending authentication countdown may sleep", pendingAuthentication)
                    assertEquals(1.seconds, duration)
                    awaitCancellation()
                }
            }
            override val faults = object : RemoteNodeFaultClassifier {
                override fun isTimeout(error: Throwable): Boolean {
                    assertTrue("Only the explicit injected I/O failures are classified", error is IOException)
                    return false
                }
                override fun isRemoteNoResponseYet(error: Throwable): Boolean {
                    assertTrue("Only the explicit telemetry failure reaches status retry classification",
                        savedGuest && telemetryFailure && error is IOException &&
                            error.message == "Deterministic guest telemetry read failure")
                    return false
                }
                override fun isBinarySessionTimeout(error: Throwable): Boolean = error("Unexpected service fault")
            }
            override fun repeaterAdmin(): RepeaterAdminPort? = if (savedGuest || regionAdmin) statusPort else null
            override fun roomAdmin(): RoomAdminPort? = null
            override fun binaryTelemetry(): BinaryTelemetryPort? = null
            override fun nodeSnapshots(): NodeSnapshotPort? = null
            override fun contactOcv(): ContactOcvPort? = null
            override fun historyStore() = store
            override fun login(): RemoteNodeLoginPort? = if (updates.value.ready) loginPort else null
            override fun connectedRadioId(): RadioId? = if (updates.value.ready) radio else null
            override fun deviceHashSize(): Int? = null
        }
        private val loginPort = object : RemoteNodeLoginPort {
            override suspend fun retrievePassword(contact: ContactDTO): String {
                assertEquals(this@NativeRemoteNodesAccessibilityTest.contact, contact)
                passwordReads++
                println("WP503_REMOTE_PASSWORD_READ|count=$passwordReads")
                return "cached-audit"
            }
            override suspend fun deletePassword(contact: ContactDTO) {
                passwordDeletes++
                error("Cancelled or failed authentication must not delete saved passwords")
            }
            override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) =
                error("The already flood-routed fixture must not reset a path")
            override suspend fun connectAsAdmin(
                radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
                onTimeoutKnown: suspend (Long) -> Unit,
            ): RemoteNodeSessionDTO {
                assertEquals(radio, radioId)
                assertEquals(this@NativeRemoteNodesAccessibilityTest.contact, contact)
                assertEquals(expectedPassword, password)
                assertEquals(expectedRemember, rememberPassword)
                assertEquals(255u.toUByte(), pathLength)
                loginAttempts++
                if (authenticationFailure) throw IOException("Deterministic authentication failure")
                assertTrue("No successful radio login is fabricated", pendingAuthentication)
                onTimeoutKnown(30)
                try {
                    awaitCancellation()
                } finally {
                    cancelledAuthentications++
                }
            }
            override suspend fun joinRoom(
                radioId: RadioId, contact: ContactDTO, password: String, rememberPassword: Boolean, pathLength: UByte,
                onTimeoutKnown: suspend (Long) -> Unit,
            ): RemoteNodeSessionDTO = error("Repeater authentication must not call the room service")
        }
        override fun services() = offline
    }
}
