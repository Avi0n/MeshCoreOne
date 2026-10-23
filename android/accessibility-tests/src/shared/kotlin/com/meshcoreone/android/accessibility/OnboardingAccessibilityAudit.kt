// AndroidOnly: WP-503 Share original permission/WiFi assertions and deterministic ports across JVM and Android runners.
package com.meshcoreone.android.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.WiFiConnectionCoordinator
import com.meshcoreone.android.feature.onboarding.WiFiSheetState
import com.meshcoreone.android.feature.onboarding.ui.PermissionsScreen
import com.meshcoreone.android.feature.onboarding.ui.WiFiConnectionSheetContent
import java.util.function.Supplier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class OnboardingAccessibilityAudit(
    private val compose: ComposeTestRule,
    private val host: Supplier<ComponentActivity>,
    private val content: (@Composable () -> Unit) -> Unit,
    private val AuditSurface: @Composable (@Composable () -> Unit) -> Unit,
    private val assertFont200Unclipped: (SemanticsNodeInteraction) -> Unit,
    private val assertScrollableFont200Unclipped: (SemanticsNodeInteraction) -> Unit,
    private val scrollWholeAllocationIntoView: (SemanticsNodeInteraction) -> Unit,
    private val assertVisibleClickTargetsAtLeast48Dp: () -> Unit,
    private val narrative: (SemanticsNode) -> String,
    private val prepareWifiIme: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    fun cancelFixtureScope() { scope.cancel() }

    fun deniedAndUndeterminedPermissionsKeepRecoveryAndContinueReachableAtFont200() {
        val requested = mutableListOf<OnboardingPermissionKind>()
        var settings = 0
        var continued = 0
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    PermissionsScreen(
                        OnboardingPermissionSnapshot(location = PermissionStatus.DENIED),
                        onRequest = { requested += it },
                        onOpenSettings = { settings++ },
                        onContinue = { continued++ },
                    )
                }
            }
        }
        val title = compose.onNodeWithText(host.get().getString(O.permissionsTitle))
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertReadable(title)
        val cardScroll = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val next = compose.onNode(hasText(host.get().getString(O.permissionsContinue)) and hasClickAction() and !cardScroll)
        assertReadable(next)
        next.performClick()
        assertEquals("Optional denial must not prevent continuing", 1, continued)
        val request = compose.onNode(hasText(host.get().getString(O.permissionsRequest)) and hasClickAction() and cardScroll)
            .performScrollTo()
        assertReadable(request)
        assertVisibleClickTargetsAtLeast48Dp()
        request.performClick()
        assertEquals(listOf(OnboardingPermissionKind.NOTIFICATIONS), requested)
        val openSettings = compose.onNodeWithText(host.get().getString(O.permissionsOpenSettings)).performScrollTo()
        assertReadable(openSettings)
        assertVisibleClickTargetsAtLeast48Dp()
        openSettings.performClick()
        assertEquals(1, settings)
        for (id in listOf(O.permissionsNotificationsTitle, O.permissionsNotificationsDescription,
            O.permissionsLocationTitle, O.permissionsLocationDescription)) {
            assertScrollableFont200Unclipped(compose.onNodeWithText(host.get().getString(id), useUnmergedTree = true))
        }
    }

    fun wifiNativeNextDoneAndFailedRetryPreserveAddressAndExposeLocalError() {
        val attempts = mutableListOf<Pair<String, Int>>()
        val failure = "Connection refused \u4e2d \u05e9\u05dc\u05d5\u05dd"
        var cancelled = 0
        val coordinator = WiFiConnectionCoordinator(scope, object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
                attempts += host to port
                return OnboardingConnectOutcome.Failed(UiText.Verbatim(failure))
            }
        }, onConnected = { error("Failure fixture must not complete onboarding") })
        mountWifi(coordinator) { cancelled++ }
        val title = compose.onNodeWithText(host.get().getString(O.wifiConnectionTitle))
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertReadable(title)
        val address = compose.onNode(hasSetTextAction() and hasText(host.get().getString(O.wifiConnectionIpAddressPlaceholder)))
        address.performClick().assertIsFocused()
        address.performTextReplacement("radio.local")
        address.performImeAction()
        val port = compose.onNode(hasSetTextAction() and hasText(host.get().getString(O.wifiConnectionPortPlaceholder)))
        port.assertIsFocused()
        port.performTextReplacement("6500")
        port.performImeAction()
        assertEquals(listOf("radio.local" to 6500), attempts)
        assertEquals("radio.local", coordinator.state.value.ipAddress)
        assertEquals("6500", coordinator.state.value.port)
        val error = compose.onNodeWithText(failure).performScrollTo()
        error.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertReadable(error)
        val retry = compose.onNodeWithText(host.get().getString(O.wifiConnectionConnect)).performScrollTo()
        assertReadable(retry)
        assertVisibleClickTargetsAtLeast48Dp()
        retry.performClick()
        assertEquals(listOf("radio.local" to 6500, "radio.local" to 6500), attempts)
        val cancel = compose.onNodeWithText(host.get().getString(L.commonCancel)).performScrollTo()
        assertReadable(cancel)
        cancel.performClick()
        assertEquals(1, cancelled)
    }

    fun wifiInitialAddressAutofocusMatchesTheNativeFrameworkControl() {
        var showWifi by mutableStateOf(false)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    if (showWifi) {
                        WiFiConnectionSheetContent(WiFiSheetState(), { error("No editing expected") },
                            { error("No editing expected") }, { error("No connection expected") }, { error("No cancel expected") })
                    } else {
                        val focus = remember { FocusRequester() }
                        OutlinedTextField("", { error("No editing expected") }, Modifier.focusRequester(focus),
                            label = { Text("Native autofocus control") })
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    }
                }
            }
        }
        val control = compose.onNode(hasSetTextAction() and hasText("Native autofocus control"))
        println("WP503_AUTOFOCUS_CONTROL|focused=${control.fetchSemanticsNode().config[SemanticsProperties.Focused]}")
        control.assertIsFocused()
        compose.runOnIdle { showWifi = true }
        val address = compose.onNode(hasSetTextAction() and hasText(host.get().getString(O.wifiConnectionIpAddressPlaceholder)))
        println("WP503_AUTOFOCUS_WIFI|focused=${address.fetchSemanticsNode().config[SemanticsProperties.Focused]}")
        address.assertIsFocused()
    }

    fun wifiConnectingHasTextualProgressAndDisablesDuplicateConnectAndCancel() {
        val outcome = CompletableDeferred<OnboardingConnectOutcome>()
        var attempts = 0
        var cancelled = 0
        val coordinator = WiFiConnectionCoordinator(scope, object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
                assertEquals("radio.local", host)
                assertEquals(5000, port)
                attempts++
                return outcome.await()
            }
        }, onConnected = { error("Pending fixture must not complete onboarding") })
        coordinator.setAddress("radio.local")
        mountWifi(coordinator) { cancelled++ }
        compose.onNodeWithText(host.get().getString(O.wifiConnectionConnect)).performScrollTo().performClick()
        val progress = compose.onNodeWithText(host.get().getString(O.wifiConnectionConnecting)).performScrollTo()
        progress.assertIsNotEnabled()
        assertReadable(progress)
        compose.onNodeWithText(host.get().getString(L.commonCancel)).performScrollTo().assertIsNotEnabled()
        assertEquals(1, attempts)
        assertEquals(0, cancelled)
        outcome.complete(OnboardingConnectOutcome.Failed(UiText.Verbatim("Radio unavailable")))
        val error = compose.onNodeWithText("Radio unavailable").performScrollTo()
        assertReadable(error)
        val connect = compose.onNodeWithText(host.get().getString(O.wifiConnectionConnect)).performScrollTo()
        assertReadable(connect)
        assertEquals(1, attempts)
    }

    private fun mountWifi(coordinator: WiFiConnectionCoordinator, onCancel: () -> Unit) {
        content {
            val state by coordinator.state.collectAsState()
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    WiFiConnectionSheetContent(state, coordinator::setAddress, coordinator::setPort,
                        coordinator::connect, onCancel)
                }
            }
        }
        prepareWifiIme()
    }

    private fun assertReadable(node: SemanticsNodeInteraction) {
        scrollWholeAllocationIntoView(node)
        node.assertIsDisplayed()
        assertFont200Unclipped(node)
        // Reading native text can advance pending IME layout before the geometry proof.
        scrollWholeAllocationIntoView(node)
        val measured = node.fetchSemanticsNode()
        val root = compose.onNodeWithTag("audit-root").fetchSemanticsNode().boundsInRoot
        val bounds = measured.boundsInRoot
        println("WP503_ONBOARDING_TEXT|text=${narrative(measured)}|bounds=$bounds|size=${measured.size}|viewport=$root")
        assertTrue("Full text must be visible in the actual native viewport",
            bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom &&
                bounds.width == measured.size.width.toFloat() && bounds.height == measured.size.height.toFloat())
    }

}
