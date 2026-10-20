// AndroidOnly: WP-502 Mounted production Region/preset consumers; controlled outcomes are not geocoder or radio evidence.
// ReferenceInput: MC1/Views/Onboarding/RegionStepView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.app.container.onboarding.ServicesRegionCatalog
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.*
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
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
class OnboardingRegionPresetComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val manualUSA = RegionSelection("US", RegionSelection.Source.MANUAL, "US-CA")

    @Suppress("DEPRECATION")
    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() { host.windowFocusChanged(false); host.pause().stop().destroy() }

    private fun text(id: Int) = host.get().getString(id)
    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
    private fun applyButton(name: String = "USA") = compose.onNodeWithText(O.presetUse(host.get().resources, name))
    private fun row(name: String) = compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun assertHeading(id: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text(id)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        assertFalse(layouts.single().didOverflowHeight)
    }

    private fun mount(fixture: Fixture) {
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture, { fixture.completed += 1 }) }
            }
        }
        button(O.welcomeGetStarted).performClick()
        button(O.permissionsContinue).assertIsEnabled().performClick()
        // Controlled already-ready connection uses the real Pair Continue callback; it never fakes a new pairing.
        button(O.deviceScanContinue).performScrollTo().performClick()
        assertNotCompleted(fixture)
    }

    private fun assertNotCompleted(fixture: Fixture) {
        assertEquals(0, fixture.completed)
        assertFalse(fixture.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
        assertTrue(fixture.flagWrites.isEmpty())
        assertEquals(0, fixture.unrelatedCalls)
        assertTrue(fixture.permissionRequests.isEmpty())
    }

    private fun pick(label: Int, query: String, name: String) {
        button(label).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText(text(C.chatsSearchPlaceholder))).performTextReplacement(query)
        compose.onNode(
            hasText(name) and hasClickAction() and !hasSetTextAction() and
                hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)),
        ).assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private fun manualUSA(fixture: Fixture) {
        mount(fixture)
        assertHeading(O.regionTitle)
        button(O.regionContinue).assertIsNotEnabled().performClick()
        assertTrue(fixture.commits.isEmpty())
        pick(O.regionCountry, "United States", "United States")
        pick(O.regionState, "California", "California")
        button(O.regionContinue).assertIsEnabled().performClick()
        assertEquals(listOf(manualUSA), fixture.commits)
        assertHeading(O.presetTitle)
        compose.onNodeWithText("910.525 MHz").assertIsDisplayed()
        button(O.presetContinue).assertIsNotEnabled()
        row("USA").assertIsNotSelected().performClick().assertIsSelected()
        assertEquals(0, fixture.resolutions.size)
        assertNotCompleted(fixture)
    }

    private fun assertHeld(fixture: Fixture, count: Int, name: String = "USA") {
        applyButton(name).assertIsNotEnabled().performClick()
        compose.runOnIdle { fixture.canApply.value = false }
        applyButton(name).assertIsNotEnabled()
        compose.runOnIdle { fixture.canApply.value = true }
        applyButton(name).assertIsNotEnabled().performClick()
        assertEquals(count, fixture.applies.size)
        assertNotCompleted(fixture)
    }

    private fun release(fixture: Fixture, outcome: OnboardingPresetOutcome) {
        compose.runOnIdle { assertTrue(fixture.applies.last().result.complete(outcome)) }
    }

    private fun assertCompletedOnce(fixture: Fixture, count: Int) {
        compose.waitForIdle()
        assertEquals(1, fixture.completed)
        assertTrue(fixture.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
        assertEquals(listOf(OnboardingState.KEY_HAS_COMPLETED to true), fixture.flagWrites)
        assertEquals(count, fixture.applies.size)
        compose.runOnIdle { fixture.canApply.value = false }
        compose.waitForIdle()
        assertEquals(1, fixture.completed)
        assertEquals(1, fixture.flagWrites.size)
        assertEquals(0, fixture.unrelatedCalls)
        assertTrue(fixture.permissionRequests.isEmpty())
    }

    @Test fun manualCountryChangesClearSubdivisionAndOnlyAcceptedHeldPresetCompletes() {
        val fixture = Fixture()
        mount(fixture)
        button(O.regionContinue).assertIsNotEnabled().performClick()
        button(O.regionUseMyLocation).assertDoesNotExist()
        button(O.regionCountry).performScrollTo().performClick()
        button(L.commonCancel).performClick()
        button(O.regionContinue).assertIsNotEnabled()
        pick(O.regionCountry, "United States", "United States")
        pick(O.regionState, "California", "California")
        pick(O.regionCountry, "United States", "United States")
        compose.onNode(hasText(text(O.regionState)) and hasText("California")).assertExists()
        pick(O.regionCountry, "Canada", "Canada")
        compose.onNode(hasText(text(O.regionProvince)) and hasText(text(S.radioPresetLocationNotSet))).assertExists()
        compose.onNodeWithText("California").assertDoesNotExist()
        pick(O.regionProvince, "Ontario", "Ontario")
        assertTrue(fixture.commits.isEmpty())
        button(O.regionContinue).performClick()
        assertEquals(listOf(RegionSelection("CA", RegionSelection.Source.MANUAL, "CA-ON")), fixture.commits)
        assertHeading(O.presetTitle)
        compose.onNodeWithText("910.525 MHz").assertIsDisplayed()
        button(O.presetContinue).assertIsNotEnabled()
        row("Canada").assertIsNotSelected().performClick().assertIsSelected()
        compose.runOnIdle { fixture.canApply.value = false }
        applyButton("Canada").assertIsNotEnabled().performClick()
        assertTrue(fixture.applies.isEmpty())
        compose.runOnIdle { fixture.canApply.value = true }
        applyButton("Canada").assertIsEnabled().performClick()
        assertHeld(fixture, 1, "Canada")
        assertEquals("ca", fixture.applies.single().id)
        release(fixture, OnboardingPresetOutcome.Applied)
        assertCompletedOnce(fixture, 1)
        assertTrue(fixture.resolutions.isEmpty())
    }

    @Test
    @Config(qualifiers = "de-w834dp-h1400dp-mdpi")
    fun typedErrorsAndRetryCancellationRetainTheSelectedPresetUntilExplicitRetrySucceeds() {
        val fixture = Fixture()
        manualUSA(fixture)
        applyButton().performClick()
        assertHeld(fixture, 1)
        release(fixture, OnboardingPresetOutcome.NotConnected)
        compose.onNodeWithText(text(O.presetErrorNotConnected)).assertIsDisplayed()
        assertNotCompleted(fixture)
        button(L.commonOk).performClick()
        row("USA").assertIsSelected()
        applyButton().assertIsEnabled().performClick()
        assertHeld(fixture, 2)
        val message = "\u914d\u7f6e \u05e9\u05dc\u05d5\u05dd"
        release(fixture, OnboardingPresetOutcome.Failed(UiText.Verbatim(message)))
        compose.onNodeWithText(message).assertIsDisplayed()
        button(S.alertRetryRetry).assertDoesNotExist()
        assertNotCompleted(fixture)
        button(L.commonOk).performClick()
        applyButton().performClick()
        assertHeld(fixture, 3)
        release(fixture, OnboardingPresetOutcome.Retryable(UiText.Verbatim(message)))
        compose.onNodeWithText(text(S.alertRetryConnectionError)).assertIsDisplayed()
        button(S.alertRetryRetry).assertIsEnabled()
        button(L.commonCancel).performClick()
        assertNotCompleted(fixture)
        assertEquals(3, fixture.applies.size)
        row("USA").assertIsSelected()
        applyButton().performClick()
        assertHeld(fixture, 4)
        release(fixture, OnboardingPresetOutcome.Retryable(UiText.Verbatim(message)))
        button(S.alertRetryRetry).performClick()
        assertHeld(fixture, 5)
        assertEquals(List(5) { "us-ca" }, fixture.applies.map { it.id })
        assertEquals(listOf(manualUSA), fixture.commits)
        release(fixture, OnboardingPresetOutcome.Applied)
        assertCompletedOnce(fixture, 5)
    }

    @Test fun cancelledApplyPropagatesFromTheProductionJobWithoutFalseErrorAndExplicitDemoOutcomeAloneCompletes() {
        val fixture = Fixture()
        manualUSA(fixture)
        applyButton().performClick()
        assertHeld(fixture, 1)
        val attempt = fixture.applies.single()
        compose.runOnIdle { assertTrue(attempt.result.completeExceptionally(CancellationException("cancelled apply"))) }
        applyButton().assertIsEnabled()
        assertTrue(attempt.owner.isCancelled)
        button(L.commonOk).assertDoesNotExist()
        button(S.alertRetryRetry).assertDoesNotExist()
        row("USA").assertIsSelected()
        assertNotCompleted(fixture)
        applyButton().performClick()
        assertHeld(fixture, 2)
        assertEquals(listOf("us-ca", "us-ca"), fixture.applies.map { it.id })
        release(fixture, OnboardingPresetOutcome.NoRadioToConfigure)
        assertCompletedOnce(fixture, 2)
    }

    @Test fun initialResolutionFailureIsSilentButExplicitLocationRetryExplainsFailureBeforeDetectedCommit() {
        val fixture = Fixture(location = PermissionStatus.GRANTED)
        mount(fixture)
        compose.onNodeWithText(text(O.regionResolving)).assertIsDisplayed()
        button(O.regionContinue).assertDoesNotExist()
        assertEquals(1, fixture.resolutions.size)
        compose.runOnIdle { assertTrue(fixture.resolutions.single().complete(null)) }
        assertHeading(O.regionTitle)
        button(O.regionContinue).assertIsNotEnabled()
        button(L.commonOk).assertDoesNotExist()
        button(O.regionUseMyLocation).performClick()
        compose.onNodeWithText(text(O.regionResolving)).assertIsDisplayed()
        assertEquals(2, fixture.resolutions.size)
        compose.runOnIdle { assertTrue(fixture.resolutions.last().complete(null)) }
        compose.onNodeWithText(text(O.regionUseMyLocationFailure)).assertIsDisplayed()
        assertTrue(fixture.commits.isEmpty())
        assertNotCompleted(fixture)
        button(L.commonOk).performClick()
        button(O.regionUseMyLocation).performClick()
        compose.onNodeWithText(text(O.regionResolving)).assertIsDisplayed()
        val detected = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
        compose.runOnIdle { assertTrue(fixture.resolutions.last().complete(detected)) }
        compose.onNodeWithText(text(O.regionDetectedTag), substring = true).assertIsDisplayed()
        button(O.regionUseThisRegion).assertIsEnabled().performClick()
        assertEquals(listOf(detected), fixture.commits)
        assertHeading(O.presetTitle)
        row("USA").assertExists()
        row("WCMesh (SoCal)").assertExists()
        assertTrue(fixture.applies.isEmpty())
        assertNotCompleted(fixture)
    }

    private data class ApplyAttempt(
        val id: String, val owner: Job,
        val result: CompletableDeferred<OnboardingPresetOutcome> = CompletableDeferred(),
    )

    private class Fixture(location: PermissionStatus = PermissionStatus.DENIED) : OnboardingFeatureDependencies {
        val resolutions = mutableListOf<CompletableDeferred<RegionSelection?>>()
        val commits = mutableListOf<RegionSelection>()
        val applies = mutableListOf<ApplyAttempt>()
        val flagWrites = mutableListOf<Pair<String, Boolean>>()
        val permissionRequests = mutableListOf<OnboardingPermissionKind>()
        val canApply = MutableStateFlow(true)
        var completed = 0
        var unrelatedCalls = 0
        private val store = InMemoryOnboardingFlagStore()
        override val flags = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = store.getBoolean(key)
            override fun setBoolean(key: String, value: Boolean) {
                flagWrites += key to value
                store.setBoolean(key, value)
            }
        }
        private fun unexpected(): Nothing { unrelatedCalls += 1; error("Unrelated onboarding port must not be invoked") }
        override val permissions = object : OnboardingPermissionPort {
            override val snapshot = MutableStateFlow(OnboardingPermissionSnapshot(
                location = location, notifications = PermissionStatus.DENIED,
                bluetooth = PermissionStatus.GRANTED, localNetwork = PermissionStatus.DENIED,
            ))
            override fun refresh(): Unit = unexpected()
        }
        override val pairing = object : OnboardingPairingPort {
            override val connection = MutableStateFlow(OnboardingConnectionSnapshot(isReady = true))
            override val pairedAccessoryCount = MutableStateFlow(0)
            override val hasLastConnectedDevice = false
            override val scanFallback: OnboardingScanFallbackPort? = null
            override suspend fun pairNewDevice(): OnboardingConnectOutcome = unexpected()
            override suspend fun retryConnection(deviceId: UUID): OnboardingConnectOutcome = unexpected()
            override suspend fun clearStalePairings(): Unit = unexpected()
        }
        override val demo = object : OnboardingDemoPort {
            override val isEnabled = MutableStateFlow(false)
            override fun unlock(): Unit = unexpected()
            override suspend fun connectDemo(): OnboardingConnectOutcome = unexpected()
        }
        override val wifi = object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome = unexpected()
        }
        override val region = object : OnboardingRegionPort {
            override val catalog = ServicesRegionCatalog { Locale.US }
            override val selection = MutableStateFlow<RegionSelection?>(null)
            override fun setSelection(selection: RegionSelection) {
                commits += selection
                this.selection.value = selection
            }
            override suspend fun resolveRegion(): RegionSelection? =
                CompletableDeferred<RegionSelection?>().also { resolutions += it }.await()
        }
        override val presets = object : OnboardingPresetPort {
            override val canApply = this@Fixture.canApply
            override suspend fun apply(presetId: String): OnboardingPresetOutcome =
                ApplyAttempt(presetId, currentCoroutineContext().job).also { applies += it }.result.await()
        }
        override fun openAppSettings(): Unit = unexpected()
        @Composable override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit =
            { permissionRequests += it }
        @Composable override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = Unit
    }
}
