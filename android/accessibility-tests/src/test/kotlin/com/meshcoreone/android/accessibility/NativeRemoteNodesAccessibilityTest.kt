// AndroidOnly: WP-503 Public remote-node catalog/history/authentication flows with deterministic ports and actual native scroll, controls and cancellation.
package com.meshcoreone.android.accessibility

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
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

    @Test fun authenticationPrefillsProtectedPasswordAndExposesLabelledRememberAndFloodState() {
        val rig = Rig().apply { ready() }
        mount(rig)
        openAuthentication(rig)
        val password = compose.onNodeWithTag("remote-login-password").performScrollTo().assertIsEnabled()
        assertTrue(password.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        assertEquals("\u2022".repeat(12), password.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val remember = rememberToggle().performScrollTo().assertIsOn().assertIsEnabled()
        assertModalTarget(remember)
        assertScrollableFont200Unclipped(compose.onNodeWithText(host.get().getString(L.remoteNodesAuthRememberPassword),
            useUnmergedTree = true))
        remember.performClick().assertIsOff()
        val flood = compose.onNode(hasText(host.get().getString(L.remoteNodesAuthFloodRouting)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performScrollTo()
        flood.assertIsOn().assertIsNotEnabled()
        assertScrollableFont200Unclipped(compose.onNodeWithText(host.get().getString(L.remoteNodesAuthNoRouteFooter),
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
            L.remoteNodesAuthPasswordTooLongRepeaters(host.get().resources, 15), useUnmergedTree = true))
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
            L.remoteNodesAuthSecondsRemaining(host.get().resources, 30), useUnmergedTree = true))
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo) and
            hasAnyAncestor(isDialog()))
        val measured = progress.fetchSemanticsNode()
        val scroll = generateSequence(measured.parent) { it.parent }.first {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }
        val viewport = scroll.boundsInRoot
        val delta = measured.positionInRoot.y + measured.size.height - viewport.bottom
        println("WP503_REMOTE_PENDING_PROGRESS|position=${measured.positionInRoot}|size=${measured.size}|viewport=$viewport|delta=$delta")
        if (delta > 0f) {
            compose.onNode(SemanticsMatcher("Actual authentication scroll viewport") { it.id == scroll.id },
                useUnmergedTree = true).performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, delta)) }
        }
        val visible = progress.assertIsDisplayed().fetchSemanticsNode()
        assertTrue("The actual progress indicator must be wholly contained after bounded non-gesture scroll",
            visible.boundsInRoot.width == visible.size.width.toFloat() &&
                visible.boundsInRoot.height == visible.size.height.toFloat() &&
                visible.boundsInRoot.left >= viewport.left && visible.boundsInRoot.right <= viewport.right &&
                visible.boundsInRoot.top >= viewport.top && visible.boundsInRoot.bottom <= viewport.bottom)
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
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    LaunchedEffect(Unit) { password.value = "cached-audit" }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Secure field control") },
                        text = {
                            OutlinedTextField(password.value, { password.value = it },
                                modifier = Modifier.testTag("secure-field-control"),
                                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                        },
                        confirmButton = { TextButton({}) { Text("Control") } },
                    )
                }
            }
        }
        val field = compose.onNodeWithTag("secure-field-control").assertIsDisplayed()
        assertEquals("\u2022".repeat(12), field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
    }

    private fun rememberToggle() = compose.onNode(hasText(host.get().getString(L.remoteNodesAuthRememberPassword)) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    private fun assertModalTarget(interaction: SemanticsNodeInteraction) {
        val node = interaction.assertIsDisplayed().fetchSemanticsNode()
        assertTrue(generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.IsDialog) })
        val viewport = generateSequence(node) { it.parent }.last().boundsInRoot
        val bounds = node.touchBoundsInRoot
        val density = host.get().resources.displayMetrics.density
        println("WP503_REMOTE_AUTH_TARGET|bounds=$bounds|viewport=$viewport")
        assertTrue("The actual enabled modal control must retain a complete 48dp target",
            bounds.width / density >= 48f && bounds.height / density >= 48f &&
                bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
    }

    private fun cancelAuthentication() {
        val cancel = compose.onNodeWithText(host.get().getString(L.remoteNodesCancel)).assertIsEnabled()
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
        compose.onNodeWithText(host.get().getString(L.remoteNodesHistoryOverviewTitle)).performScrollTo().performClick()
        compose.onNodeWithTag("remote-history-content").assertIsDisplayed()
    }

    private inner class Rig : RemoteNodesUiDependencies {
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
        var expectedPassword = "cached-audit"
        var expectedRemember = true
        override val updates = MutableStateFlow(RemoteNodesConnection(radio, null, false))
        fun ready() { updates.value = RemoteNodesConnection(radio, "audit-generation", true) }
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
                override suspend fun sleep(duration: Duration): Nothing {
                    assertTrue("Only the real pending authentication countdown may sleep", pendingAuthentication)
                    assertEquals(1.seconds, duration)
                    awaitCancellation()
                }
            }
            override val faults = object : RemoteNodeFaultClassifier {
                override fun isTimeout(error: Throwable): Boolean {
                    assertTrue("Only the explicit injected authentication error is classified", error is IOException)
                    return false
                }
                override fun isRemoteNoResponseYet(error: Throwable): Boolean = error("Unexpected service fault")
                override fun isBinarySessionTimeout(error: Throwable): Boolean = error("Unexpected service fault")
            }
            override fun repeaterAdmin(): RepeaterAdminPort? = null
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
