// AndroidOnly: WP-502 Production onboarding pairing consumers with controlled ports; not OS pairing, radio or device evidence.
// ReferenceInput: MC1/Views/Onboarding/DeviceScanView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.feature.onboarding.*
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingPairingComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val retryId = UUID.fromString("50200000-0000-0000-0000-000000000305")

    @Suppress("DEPRECATION")
    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() { host.windowFocusChanged(false); host.pause().stop().destroy() }

    private fun text(id: Int) = host.get().getString(id)
    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
    private fun pairTitle() = compose.onNode(
        hasText(text(O.deviceScanTitle)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
    )

    private fun mount(fixture: Fixture) {
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture, { fixture.completed += 1 }) }
            }
        }
        button(O.welcomeGetStarted).assertIsDisplayed().performClick()
        button(O.permissionsContinue).assertIsEnabled().performClick()
        pairTitle().assertIsDisplayed()
        button(O.deviceScanAddDevice).performScrollTo().assertIsEnabled()
        val layouts = mutableListOf<TextLayoutResult>()
        pairTitle().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        assertFalse(layouts.single().didOverflowHeight)
        assertTrue(fixture.attempts.isEmpty())
        assertNotCompleted(fixture)
    }

    private fun assertNotCompleted(fixture: Fixture, expectedRequests: Int = 0, expectedSettings: Int = 0) {
        assertEquals(0, fixture.completed)
        assertFalse(fixture.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
        assertEquals(0, fixture.unrelatedCalls)
        assertEquals(List(expectedRequests) { OnboardingPermissionKind.BLUETOOTH }, fixture.permissionRequests)
        assertEquals(expectedSettings, fixture.settingsOpens)
        val snapshot = fixture.permissions.snapshot.value
        assertEquals(PermissionStatus.DENIED, snapshot.location)
        assertEquals(PermissionStatus.DENIED, snapshot.notifications)
        assertEquals(PermissionStatus.DENIED, snapshot.localNetwork)
    }

    private fun assertHeld(fixture: Fixture, expectedCount: Int, expectedRequests: Int = 0, expectedSettings: Int = 0) {
        button(O.deviceScanConnecting).assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            fixture.connection.value = OnboardingConnectionSnapshot(isBusy = true)
        }
        button(O.deviceScanConnecting).assertIsNotEnabled().performClick()
        compose.runOnIdle { fixture.connection.value = OnboardingConnectionSnapshot() }
        button(O.deviceScanConnecting).assertIsNotEnabled()
        assertEquals(expectedCount, fixture.attempts.size)
        compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
        assertNotCompleted(fixture, expectedRequests, expectedSettings)
    }

    private fun release(fixture: Fixture, outcome: OnboardingConnectOutcome) {
        compose.runOnIdle { assertTrue(fixture.attempts.last().result.complete(outcome)) }
    }

    private fun assertExactlyOneRegionEntry(
        fixture: Fixture, expectedCount: Int, expectedRequests: Int = 0, expectedSettings: Int = 0,
    ) {
        compose.onNodeWithText(text(O.regionTitle)).assertIsDisplayed()
        pairTitle().assertDoesNotExist()
        assertNotCompleted(fixture, expectedRequests, expectedSettings)
        // Invoke the actual flow's Back callback, not a copied navigation policy or an OS Back claim.
        compose.runOnIdle { requireNotNull(fixture.back)() }
        pairTitle().assertIsDisplayed()
        compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
        button(O.deviceScanAddDevice).performScrollTo().assertIsEnabled()
        assertEquals(expectedCount, fixture.attempts.size)
        assertNotCompleted(fixture, expectedRequests, expectedSettings)
    }

    @Test fun heldPrimaryHasOneAttemptAndOnlyExplicitSuccessPushesOneRegionEntry() {
        val fixture = Fixture()
        mount(fixture)
        compose.runOnIdle { fixture.connection.value = OnboardingConnectionSnapshot(isBusy = true) }
        button(O.deviceScanConnecting).assertIsNotEnabled().performClick()
        assertTrue(fixture.attempts.isEmpty())
        compose.runOnIdle { fixture.connection.value = OnboardingConnectionSnapshot() }
        button(O.deviceScanAddDevice).assertIsEnabled().performClick()
        assertEquals(listOf(Action.PAIR), fixture.attempts.map { it.action })
        assertHeld(fixture, 1)
        release(fixture, OnboardingConnectOutcome.Connected)
        assertExactlyOneRegionEntry(fixture, 1)
    }

    @Test
    @Config(qualifiers = "de-w834dp-h1400dp-mdpi")
    fun otherAppFailureKeepsExactRetryTargetAcrossDismissalAndAnotherFailureUntilSuccess() {
        val fixture = Fixture()
        mount(fixture)
        button(O.deviceScanAddDevice).performClick()
        assertHeld(fixture, 1)
        val firstError = "\u5176\u4ed6\u5e94\u7528 \u05e9\u05dc\u05d5\u05dd"
        release(fixture, OnboardingConnectOutcome.Failed(UiText.Verbatim(firstError), retryId))
        compose.onNodeWithText(firstError).assertIsDisplayed()
        button(L.commonOk).performClick()
        compose.onNodeWithText(firstError).assertDoesNotExist()
        button(O.deviceScanRetryConnection).performScrollTo().assertIsEnabled().performClick()
        assertEquals(retryId, fixture.attempts.last().deviceId)
        assertHeld(fixture, 2)
        release(fixture, OnboardingConnectOutcome.Failed(UiText.Verbatim("still held")))
        compose.onNodeWithText("still held").assertIsDisplayed()
        button(L.commonOk).performClick()
        button(O.deviceScanRetryConnection).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(Action.PAIR, Action.RETRY, Action.RETRY), fixture.attempts.map { it.action })
        assertEquals(listOf(retryId, retryId), fixture.attempts.drop(1).map { it.deviceId })
        assertHeld(fixture, 3)
        release(fixture, OnboardingConnectOutcome.Connected)
        assertExactlyOneRegionEntry(fixture, 3)
    }

    @Test fun benignCancelledAndIgnoredOutcomesLeaveTheActualPairStepReusableWithoutAnErrorOrCompletion() {
        val fixture = Fixture()
        mount(fixture)
        for ((index, outcome) in listOf(OnboardingConnectOutcome.Cancelled, OnboardingConnectOutcome.Ignored).withIndex()) {
            button(O.deviceScanAddDevice).performScrollTo().performClick()
            assertHeld(fixture, index + 1)
            release(fixture, outcome)
            button(O.deviceScanAddDevice).assertIsEnabled()
            button(L.commonOk).assertDoesNotExist()
            compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
            assertEquals(index + 1, fixture.attempts.size)
            assertNotCompleted(fixture)
        }
        button(O.deviceScanAddDevice).performClick()
        assertHeld(fixture, 3)
        release(fixture, OnboardingConnectOutcome.Connected)
        assertEquals(listOf(Action.PAIR, Action.PAIR, Action.PAIR), fixture.attempts.map { it.action })
        assertExactlyOneRegionEntry(fixture, 3)
    }

    @Test fun threeTitleTapsAloneUnlockDemoAndDemoPrimaryPrecedesAnExistingRetryTarget() {
        val fixture = Fixture()
        mount(fixture)
        button(O.deviceScanAddDevice).performClick()
        release(fixture, OnboardingConnectOutcome.Failed(UiText.Verbatim("other app"), retryId))
        button(L.commonOk).performClick()
        button(O.deviceScanRetryConnection).performScrollTo().assertIsEnabled()
        repeat(2) { pairTitle().performScrollTo().performClick() }
        assertEquals(0, fixture.unlocks)
        button(O.deviceScanContinueDemo).assertDoesNotExist()
        pairTitle().performClick()
        compose.onNodeWithText(text(O.deviceScanDemoModeAlertTitle)).assertIsDisplayed()
        assertEquals(1, fixture.unlocks)
        assertEquals(1, fixture.attempts.size)
        button(L.commonOk).performClick()
        button(O.deviceScanRetryConnection).assertDoesNotExist()
        button(O.deviceScanAddDevice).assertDoesNotExist()
        button(O.deviceScanContinueDemo).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(Action.PAIR, Action.DEMO), fixture.attempts.map { it.action })
        assertHeld(fixture, 2)
        release(fixture, OnboardingConnectOutcome.Connected)
        compose.onNodeWithText(text(O.regionTitle)).assertIsDisplayed()
        assertNotCompleted(fixture)
        assertEquals(2, fixture.attempts.size)
    }

    private fun finishBluetoothRequest(fixture: Fixture, status: PermissionStatus) {
        compose.runOnIdle {
            fixture.platformPermissions = fixture.platformPermissions.copy(bluetooth = status)
            requireNotNull(fixture.permissionResult)()
        }
    }

    private fun assertBlocked(fixture: Fixture, expectedRequests: Int, expectedSettings: Int = 0) {
        assertTrue(fixture.attempts.isEmpty())
        button(O.deviceScanAddDevice).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
        button(L.commonOk).assertDoesNotExist()
        assertNotCompleted(fixture, expectedRequests, expectedSettings)
    }

    private fun assertWiFiRemainsAvailable(fixture: Fixture, expectedRequests: Int, expectedSettings: Int) {
        button(O.deviceScanConnectViaWifi).performScrollTo().assertIsEnabled().performClick()
        compose.onNode(
            hasText(text(O.wifiConnectionTitle)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        button(L.commonCancel).assertIsEnabled().performClick()
        assertBlocked(fixture, expectedRequests, expectedSettings)
    }

    @Test fun justInTimeGrantResumesOneHeldPairAndRepeatedResultCannotStartAnother() {
        val fixture = Fixture(bluetooth = PermissionStatus.NOT_DETERMINED)
        mount(fixture)
        assertEquals(0, fixture.permissionRefreshes)
        button(O.deviceScanAddDevice).performClick()
        assertBlocked(fixture, expectedRequests = 1)
        assertEquals(PermissionStatus.NOT_DETERMINED, fixture.permissions.snapshot.value.bluetooth)
        compose.waitForIdle()
        assertBlocked(fixture, expectedRequests = 1)

        finishBluetoothRequest(fixture, PermissionStatus.GRANTED)
        assertEquals(1, fixture.permissionRefreshes)
        assertEquals(PermissionStatus.GRANTED, fixture.permissions.snapshot.value.bluetooth)
        assertEquals(listOf(Action.PAIR), fixture.attempts.map { it.action })
        assertHeld(fixture, 1, expectedRequests = 1)
        finishBluetoothRequest(fixture, PermissionStatus.GRANTED)
        assertEquals(2, fixture.permissionRefreshes)
        assertHeld(fixture, 1, expectedRequests = 1)
        release(fixture, OnboardingConnectOutcome.Connected)
        compose.onNodeWithText(text(O.regionTitle)).assertIsDisplayed()
        finishBluetoothRequest(fixture, PermissionStatus.GRANTED)
        assertEquals(3, fixture.permissionRefreshes)
        assertExactlyOneRegionEntry(fixture, 1, expectedRequests = 1)
    }

    @Test
    @Config(qualifiers = "de-w834dp-h1400dp-mdpi")
    fun deniedResultOffersSettingsAndWiFiWithoutPromptLoopAndGrantNeedsNewIntent() {
        val fixture = Fixture(bluetooth = PermissionStatus.NOT_DETERMINED)
        mount(fixture)
        button(O.deviceScanAddDevice).performClick()
        assertBlocked(fixture, expectedRequests = 1)
        finishBluetoothRequest(fixture, PermissionStatus.DENIED)
        assertEquals(1, fixture.permissionRefreshes)
        assertEquals(PermissionStatus.DENIED, fixture.permissions.snapshot.value.bluetooth)
        compose.onNodeWithText(text(O.deviceScannerBluetoothUnauthorizedTitle)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(O.deviceScannerBluetoothUnauthorizedMessage)).performScrollTo().assertIsDisplayed()
        button(O.permissionsOpenSettings).performScrollTo().assertIsEnabled().performClick()
        assertBlocked(fixture, expectedRequests = 1, expectedSettings = 1)
        button(O.deviceScanAddDevice).performClick()
        finishBluetoothRequest(fixture, PermissionStatus.DENIED)
        assertEquals(2, fixture.permissionRefreshes)
        assertBlocked(fixture, expectedRequests = 1, expectedSettings = 1)
        assertWiFiRemainsAvailable(fixture, expectedRequests = 1, expectedSettings = 1)

        // A later settings grant refreshes the snapshot, but the denied request's intent was consumed.
        finishBluetoothRequest(fixture, PermissionStatus.GRANTED)
        assertEquals(3, fixture.permissionRefreshes)
        assertBlocked(fixture, expectedRequests = 1, expectedSettings = 1)
        button(O.deviceScanAddDevice).performClick()
        compose.onNodeWithText(text(O.deviceScannerBluetoothUnauthorizedTitle)).assertDoesNotExist()
        assertEquals(listOf(Action.PAIR), fixture.attempts.map { it.action })
        assertHeld(fixture, 1, expectedRequests = 1, expectedSettings = 1)
        release(fixture, OnboardingConnectOutcome.Connected)
        assertExactlyOneRegionEntry(fixture, 1, expectedRequests = 1, expectedSettings = 1)
    }

    @Test fun bluetoothOffOffersSettingsAndWiFiThenExplicitAddRechecksTheAdapterWithoutRequestingPermission() {
        val fixture = Fixture(adapterEnabled = false)
        mount(fixture)
        button(O.deviceScanAddDevice).performClick()
        compose.onNodeWithText(text(O.deviceScannerBluetoothOffTitle)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(O.deviceScannerBluetoothOffMessage)).performScrollTo().assertIsDisplayed()
        button(O.permissionsOpenSettings).performScrollTo().assertIsEnabled().performClick()
        assertBlocked(fixture, expectedRequests = 0, expectedSettings = 1)
        assertWiFiRemainsAvailable(fixture, expectedRequests = 0, expectedSettings = 1)
        compose.runOnIdle {
            fixture.platformPermissions = fixture.platformPermissions.copy(bluetoothAdapterEnabled = true)
        }
        finishBluetoothRequest(fixture, PermissionStatus.GRANTED)
        assertEquals(1, fixture.permissionRefreshes)
        assertBlocked(fixture, expectedRequests = 0, expectedSettings = 1)
        button(O.deviceScanAddDevice).performClick()
        compose.onNodeWithText(text(O.deviceScannerBluetoothOffTitle)).assertDoesNotExist()
        assertEquals(listOf(Action.PAIR), fixture.attempts.map { it.action })
        assertHeld(fixture, 1, expectedRequests = 0, expectedSettings = 1)
        release(fixture, OnboardingConnectOutcome.Connected)
        assertExactlyOneRegionEntry(fixture, 1, expectedRequests = 0, expectedSettings = 1)
    }

    private enum class Action { PAIR, RETRY, DEMO }
    private data class Attempt(
        val action: Action, val deviceId: UUID? = null,
        val result: CompletableDeferred<OnboardingConnectOutcome> = CompletableDeferred(),
    )

    private class Fixture(
        bluetooth: PermissionStatus = PermissionStatus.GRANTED, adapterEnabled: Boolean = true,
    ) : OnboardingFeatureDependencies {
        val attempts = mutableListOf<Attempt>()
        val permissionRequests = mutableListOf<OnboardingPermissionKind>()
        val connection = MutableStateFlow(OnboardingConnectionSnapshot())
        var completed = 0
        var unrelatedCalls = 0
        var unlocks = 0
        var back: (() -> Unit)? = null
        var permissionResult: (() -> Unit)? = null
        var permissionRefreshes = 0
        var settingsOpens = 0
        var platformPermissions = OnboardingPermissionSnapshot(
            location = PermissionStatus.DENIED, notifications = PermissionStatus.DENIED,
            bluetooth = bluetooth, localNetwork = PermissionStatus.DENIED, bluetoothAdapterEnabled = adapterEnabled,
        )
        private val permissionSnapshot = MutableStateFlow(platformPermissions)
        override val flags = InMemoryOnboardingFlagStore()
        override val permissions = object : OnboardingPermissionPort {
            override val snapshot = permissionSnapshot
            override fun refresh() {
                permissionRefreshes += 1
                permissionSnapshot.value = platformPermissions
            }
        }
        private suspend fun attempt(action: Action, id: UUID? = null): OnboardingConnectOutcome {
            val call = Attempt(action, id)
            attempts += call
            return call.result.await()
        }
        private fun unexpected(): Nothing { unrelatedCalls += 1; error("Unrelated onboarding port must not be invoked") }
        override val pairing = object : OnboardingPairingPort {
            override val connection = this@Fixture.connection
            override val pairedAccessoryCount = MutableStateFlow(0)
            override val hasLastConnectedDevice = false
            override val scanFallback: OnboardingScanFallbackPort? = null
            override suspend fun pairNewDevice() = attempt(Action.PAIR)
            override suspend fun retryConnection(deviceId: UUID) = attempt(Action.RETRY, deviceId)
            override suspend fun clearStalePairings(): Unit = unexpected()
        }
        override val demo = object : OnboardingDemoPort {
            override val isEnabled = MutableStateFlow(false)
            override fun unlock() { unlocks += 1; isEnabled.value = true }
            override suspend fun connectDemo() = attempt(Action.DEMO)
        }
        override val wifi = object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome = unexpected()
        }
        override val region = object : OnboardingRegionPort {
            override val selection = MutableStateFlow<RegionSelection?>(null)
            override fun setSelection(selection: RegionSelection): Unit = unexpected()
            override suspend fun resolveRegion(): RegionSelection? = unexpected()
            override val catalog = object : OnboardingRegionCatalog {
                override fun countries() = emptyList<OnboardingCountry>()
                override fun subdivisions(countryCode: String?) = emptyList<OnboardingSubdivision>()
                override fun showsSubdivisionPicker(countryCode: String?) = false
                override fun administrativeAreaKind(countryCode: String?) = AdministrativeAreaKind.STATE
                override fun displayName(region: RegionSelection): String = unexpected()
                override fun countryDisplayName(countryCode: String): String = unexpected()
                override fun subdivisionDisplayName(code: String): String? = unexpected()
                override fun presets(region: RegionSelection?) = emptyList<OnboardingPresetOption>()
            }
        }
        override val presets = object : OnboardingPresetPort {
            override val canApply = MutableStateFlow(false)
            override suspend fun apply(presetId: String): OnboardingPresetOutcome = unexpected()
        }
        override fun openAppSettings() { settingsOpens += 1 }
        @Composable override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit {
            SideEffect { permissionResult = onResult }
            return { permissionRequests += it }
        }
        @Composable override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) {
            SideEffect { back = if (enabled) onBack else null }
        }
    }
}
