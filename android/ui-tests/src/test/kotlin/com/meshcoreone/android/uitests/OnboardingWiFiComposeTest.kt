// AndroidOnly: WP-502 Mounted production onboarding flow with controlled ports; not actual socket, permission or radio evidence.
// ReferenceInput: MC1/Views/Onboarding/WiFiConnectionSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Tests/Views/Components/WiFiHostValidationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.onboarding.*
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import java.util.Locale
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
class OnboardingWiFiComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var frame: ScreenCase
    private var effectiveScheme: ColorScheme? = null

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    private fun text(id: Int) = host.get().createConfigurationContext(
        Configuration(host.get().resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(frame.locale)))
        },
    ).getString(id)

    private fun mount(fixture: Fixture, case: ScreenCase) {
        frame = case
        host.get().setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(frame.locale)))
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, frame.fontScale),
                LocalLayoutDirection provides if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(frame.theme)),
                    colorSchemePreference = frame.preference, systemDark = frame.systemDark, motionScale = 0f,
                ) {
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect { effectiveScheme = scheme }
                    OnboardingFlow(fixture, { fixture.completed += 1 })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(frame.expectedScheme, effectiveScheme)
        button(O.welcomeGetStarted).assertIsDisplayed().performClick()
        button(O.permissionsContinue).assertIsEnabled().performClick()
        button(O.deviceScanConnectViaWifi).performScrollTo().performClick()
        address().assertIsDisplayed().assertIsFocused().assertTextContains("")
        port().assertTextContains("5000")
        button(O.wifiConnectionConnect).assertIsNotEnabled()
    }

    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())
    private fun address() = compose.onNode(hasSetTextAction() and hasText(text(O.wifiConnectionIpAddressPlaceholder)))
    private fun port() = compose.onNode(hasSetTextAction() and hasText(text(O.wifiConnectionPortPlaceholder)))

    private fun enter(hostname: String, port: String = "5000") {
        address().performScrollTo().performTextReplacement(hostname)
        port().performScrollTo().performTextReplacement(port)
    }

    private fun assertNotCompleted(fixture: Fixture) {
        assertEquals(0, fixture.completed)
        assertFalse(fixture.flags.getBoolean(OnboardingState.KEY_HAS_COMPLETED))
        assertTrue(fixture.permissionRequests.isEmpty())
        assertEquals(0, fixture.unrelatedCalls)
    }

    @Test fun frozenHostFamiliesAndPortGuardsReachTheRealFlowBeforeNormalizedKeyboardSubmit() {
        val fixture = Fixture()
        mount(fixture, CoverageMatrix.themeFrames.first().copy(fontScale = 2f))
        val originalHosts = listOf(
            "192.168.1.50" to true, "radio.local" to true, "example.com" to true,
            "repeater" to true, "  radio.local  " to true, "" to false,
            "999.999.999.999" to false, "192.168.1" to false, "http://radio.local" to false,
        )
        for ((hostname, valid) in originalHosts) {
            enter(hostname)
            if (valid) button(O.wifiConnectionConnect).assertIsEnabled()
            else button(O.wifiConnectionConnect).assertIsNotEnabled()
            assertTrue(fixture.attempts.isEmpty())
        }
        for ((value, valid) in listOf("" to false, "0" to false, "-1" to false, "65536" to false, "5000x" to false, "1" to true, "65535" to true)) {
            enter("radio.local", value)
            if (valid) button(O.wifiConnectionConnect).assertIsEnabled()
            else button(O.wifiConnectionConnect).assertIsNotEnabled()
        }
        enter("radio.local", "70000")
        port().performImeAction()
        compose.onNodeWithText(text(O.wifiConnectionErrorInvalidPort)).assertIsDisplayed()
        assertTrue(fixture.attempts.isEmpty())
        enter("192,168,1,50")
        address().assertTextContains("192.168.1.50")
        compose.onNodeWithContentDescription(text(O.wifiConnectionIpAddressClearAccessibility)).performClick()
        button(O.wifiConnectionConnect).assertIsNotEnabled()
        enter("  radio.local  ", "65535")
        address().performImeAction()
        port().assertIsFocused().performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("radio.local" to 65535), fixture.attempts.map { it.host to it.port })
        button(O.wifiConnectionConnecting).assertIsNotEnabled()
        assertNotCompleted(fixture)
        compose.runOnIdle { fixture.attempts.single().result.complete(OnboardingConnectOutcome.Connected) }
        compose.onNodeWithText(text(O.regionTitle)).assertIsDisplayed()
        address().assertDoesNotExist()
        assertNotCompleted(fixture)
    }

    @Test
    @Config(qualifiers = "w834dp-h1400dp-mdpi")
    fun heldFailureRetainsLocalizedFieldsAndRetryAloneAdvancesToRegion() {
        val fixture = Fixture()
        val ember = CoverageMatrix.themeFrames.single { it.theme == ThemeId.EMBER }
        mount(fixture, ember.copy(fontScale = 2f, locale = "zh-Hans", rtl = true))
        enter("mesh.local", "5000")
        button(O.wifiConnectionConnect).performScrollTo().performClick()
        button(O.wifiConnectionConnecting).assertIsNotEnabled()
        button(L.commonCancel).assertIsNotEnabled()
        // The real IME path must not start a second request while the first is held.
        port().performImeAction()
        compose.waitForIdle()
        assertEquals(1, fixture.attempts.size)
        assertNotCompleted(fixture)
        val error = "\u65e0\u6cd5\u8fde\u63a5 \u05e9\u05dc\u05d5\u05dd"
        compose.runOnIdle {
            fixture.attempts.single().result.complete(OnboardingConnectOutcome.Failed(UiText.Verbatim(error)))
        }
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        address().assertTextContains("mesh.local")
        port().assertTextContains("5000")
        button(L.commonCancel).assertIsEnabled()
        button(O.wifiConnectionConnect).assertIsEnabled()
        compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
        assertNotCompleted(fixture)
        button(O.wifiConnectionConnect).performScrollTo().performClick()
        compose.onNodeWithText(error).assertDoesNotExist()
        assertEquals(listOf("mesh.local" to 5000, "mesh.local" to 5000), fixture.attempts.map { it.host to it.port })
        compose.runOnIdle { fixture.attempts.last().result.complete(OnboardingConnectOutcome.Connected) }
        compose.onNodeWithText(text(O.regionTitle)).assertIsDisplayed()
        address().assertDoesNotExist()
        assertNotCompleted(fixture)
    }

    @Test fun benignCancelledAttemptDoesNotAdvanceAndReopenedSheetResetsOnlyItsOwnInputs() {
        val fixture = Fixture()
        mount(fixture, CoverageMatrix.themeFrames.first { it.expectedScheme == ColorScheme.DARK }.copy(locale = "de"))
        enter("cancelled.local", "6000")
        button(O.wifiConnectionConnect).performScrollTo().performClick()
        compose.runOnIdle { fixture.attempts.single().result.complete(OnboardingConnectOutcome.Cancelled) }
        address().assertTextContains("cancelled.local")
        port().assertTextContains("6000")
        button(O.wifiConnectionConnect).assertIsEnabled()
        compose.onNodeWithText(text(O.regionTitle)).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        button(L.commonCancel).performClick()
        address().assertDoesNotExist()
        button(O.deviceScanConnectViaWifi).performScrollTo().performClick()
        address().assertIsFocused().assertTextContains("")
        port().assertTextContains("5000")
        button(O.wifiConnectionConnect).assertIsNotEnabled()
        assertEquals(1, fixture.attempts.size)
        assertNotCompleted(fixture)
    }

    private data class Attempt(
        val host: String, val port: Int,
        val result: CompletableDeferred<OnboardingConnectOutcome> = CompletableDeferred(),
    )

    private class Fixture : OnboardingFeatureDependencies {
        val attempts = mutableListOf<Attempt>()
        val permissionRequests = mutableListOf<OnboardingPermissionKind>()
        var completed = 0
        var unrelatedCalls = 0
        override val flags = InMemoryOnboardingFlagStore()
        override val permissions = object : OnboardingPermissionPort {
            override val snapshot = MutableStateFlow(OnboardingPermissionSnapshot(
                location = PermissionStatus.DENIED, notifications = PermissionStatus.DENIED,
                bluetooth = PermissionStatus.DENIED, localNetwork = PermissionStatus.DENIED,
            ))
            override fun refresh() = Unit
        }
        override val wifi = object : OnboardingWiFiPort {
            override suspend fun connect(host: String, port: Int): OnboardingConnectOutcome {
                val attempt = Attempt(host, port)
                attempts += attempt
                return attempt.result.await()
            }
        }
        private fun unexpected(): Nothing { unrelatedCalls += 1; error("Unrelated onboarding port must not be invoked") }
        override val pairing = object : OnboardingPairingPort {
            override val connection = MutableStateFlow(OnboardingConnectionSnapshot())
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
        override fun openAppSettings(): Unit = unexpected()
        @Composable override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit =
            { permissionRequests += it }
        @Composable override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = Unit
    }
}
