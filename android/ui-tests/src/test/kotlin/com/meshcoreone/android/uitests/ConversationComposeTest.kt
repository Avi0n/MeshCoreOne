// AndroidOnly: WP-502 Actual native SDK31 production-content assertions; not device or golden approval.
package com.meshcoreone.android.uitests

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.ThemeId
import java.io.File
import org.junit.After
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
@Config(sdk = [31], qualifiers = "w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var cases: ConversationScreenCases

    @Before fun mount() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        cases = ConversationScreenCases(
            compose, host.get(), setContent = { host.get().setContent(content = it) },
        )
        cases.mount()
    }

    @After fun close() { host.pause().stop().destroy() }

    @Test fun draftFocusAndRetrySurviveResize() = cases.draftFocusAndRetrySurviveCurrentWindowChanges()
    @Test fun disconnectedAndByteOverflowDisableTheRealComposer() = cases.offlineAndUtf8OverflowDoNotInvokeSend()
    @Test fun timelineLoadingEmptyAndFailureHaveAccessibleRecovery() =
        cases.loadingEmptyAndFailureRemainDistinctAndRetryIsAccessible()
    @Test fun hardwareReturnUsesSendGuardsAndNormalMultilineFallback() =
        cases.hardwareReturnRespectsSendGuardsAndMultilineFallback()
    @Test fun localImageRecoveryViewerAndPreviewSafetyUseNoNetwork() =
        localContent().imageFailureRetryViewerAndPreviewSafety(output("content"))
    @Test fun offlineLocalMapRecoveryDoesNotGateCoordinateActions() =
        localContent().offlineMapFailureRetryAndCoordinateAction(output("content"))

    private fun localContent() = LocalContentCases(
        compose, host.get(), setContent = { host.get().setContent(content = it) },
    )

    @Test fun allNineteenEffectiveThemeFramesRenderDeterministically() {
        org.junit.Assert.assertEquals(10, CoverageMatrix.themeFrames.map { it.theme }.toSet().size)
        org.junit.Assert.assertEquals(19, CoverageMatrix.themeFrames.size)
        CoverageMatrix.assertThemeWindowFontCoverage()
        for (case in CoverageMatrix.themeWindowFontCases) cases.capture(case, output("themes"))
        cases.show(ScreenCase(
            "ember-forced-dark", theme = ThemeId.EMBER,
            preference = AppColorSchemePreference.LIGHT, expectedScheme = ColorScheme.DARK,
        ))
    }

    @Test fun systemPreferenceAndForcedSchemeRequestsRenderDeterministically() {
        for (case in CoverageMatrix.systemFrames) cases.capture(case, output("system"))
    }

    @Test fun localeWindowFontDirectionAndDeliveryStatePairsAreComplete() {
        CoverageMatrix.assertCompletePairCoverage()
        for (case in CoverageMatrix.pairwiseStates) cases.capture(case, output("states"))
    }

    private fun output(group: String) = File(
        requireNotNull(System.getProperty("wp502ScreenshotDirectory")), "sdk31/$group",
    )
}
