// AndroidOnly: WP-502 Reuse production-content assertions on the actual API31/37 instrumentation host.
package com.meshcoreone.android.uitests

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshcoreone.android.MainActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var cases: ConversationScreenCases

    @Before fun mount() {
        assertTrue("Required actual API31/37 host", Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 37)
        cases = ConversationScreenCases(
            compose, compose.activity,
            setContent = { content -> compose.runOnUiThread { compose.activity.setContent(content = content) } },
            useCurrentDeviceWindow = true,
        )
        cases.mount()
    }

    @Test fun offlineAndByteOverflowAreCapabilityIndependent() = cases.offlineAndUtf8OverflowDoNotInvokeSend()
    @Test fun actualDeviceShowsDistinctTimelineLoadingEmptyAndFailure() =
        cases.loadingEmptyAndFailureRemainDistinctAndRetryIsAccessible()
    @Test fun actualDeviceHardwareReturnUsesSendGuardsAndMultilineFallback() =
        cases.hardwareReturnRespectsSendGuardsAndMultilineFallback()
    @Test fun actualDeviceLocalImageRecoveryAndPreviewSafety() =
        localContent().imageFailureRetryViewerAndPreviewSafety(output("content"))
    @Test fun actualDeviceOfflineLocalMapRecoveryAndCoordinateActions() =
        localContent().offlineMapFailureRetryAndCoordinateAction(output("content"))

    private fun localContent() = LocalContentCases(
        compose, compose.activity,
        setContent = { content -> compose.runOnUiThread { compose.activity.setContent(content = content) } },
        device = true,
    )
    @Test fun retainedDraftAndRetryWorkOnTheCurrentDeviceWindow() =
        cases.draftFocusAndRetrySurviveCurrentWindowChanges(widths = emptyList())

    @Test fun actualDeviceRendersEveryEffectiveThemeFrame() {
        val frames = CoverageMatrix.themeFrames.flatMap { frame ->
            CoverageMatrix.fonts.map { font -> frame.copy(id = "${frame.id}-f${font.toInt()}", fontScale = font) }
        }
        for (case in frames + CoverageMatrix.systemFrames) cases.capture(case, output("themes"))
    }

    private fun output(group: String) = File(
        requireNotNull(compose.activity.getExternalFilesDir(null)), "wp502/sdk${Build.VERSION.SDK_INT}/$group",
    )
}
