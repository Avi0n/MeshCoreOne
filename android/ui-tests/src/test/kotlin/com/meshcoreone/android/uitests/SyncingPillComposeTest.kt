// AndroidOnly: WP-502 Mounted exact Custom Error parameter through the production status pill; not primary-case or device acceptance.
// ReferenceInput: MC1Tests/Views/SyncingPillViewTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Components/SyncingPillView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/State/StatusPillState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.ui.SharedEmittedForeground
import com.meshcoreone.android.core.ui.SharedEmittedIcon
import com.meshcoreone.android.core.ui.SharedPaintedSurface
import com.meshcoreone.android.core.ui.StatusPillState
import com.meshcoreone.android.core.ui.SyncingPillView
import com.meshcoreone.android.core.ui.UiText
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
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncingPillComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Suppress("DEPRECATION")
    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
        assertEquals(2f, resources.configuration.fontScale, 0f)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    @Test fun customErrorRemainsVerbatimInTheProductionFailureBranchAcrossRecomposition() {
        val originalMessage = UiText.Verbatim("Custom Error")
        val failure = StatusPillState.Failed(originalMessage)
        val revision = mutableStateOf(0)
        var disconnectedTaps = 0
        var expectedErrorSurface: Color? = null
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    val errorSurface = MaterialTheme.colorScheme.errorContainer
                    SideEffect { expectedErrorSurface = errorSurface }
                    Box(Modifier.width(360.dp).height(300.dp)) {
                        SyncingPillView(
                            failure, Modifier.testTag("custom-failure:${revision.value}"),
                            onDisconnectedTap = { disconnectedTaps++ },
                        )
                    }
                }
            }
        }
        fun assertOriginalFailure() {
            assertTrue(failure.isFailure)
            assertSame(originalMessage, failure.message)
            assertEquals("Custom Error", failure.text.resolve(host.get().resources))
            val tag = "custom-failure:${revision.value}"
            val pill = compose.onNodeWithTag(tag).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val layout = assertMountedText(
                compose, pill, "Custom Error", 2f, LayoutDirection.Ltr, "original verbatim custom failure",
            )
            assertEquals(FontWeight.Bold, layout.layoutInput.style.fontWeight)
            val actual = pill.fetchSemanticsNode().config
            assertEquals(requireNotNull(expectedErrorSurface), actual[SharedPaintedSurface])
            assertEquals(layout.layoutInput.style.color, actual[SharedEmittedForeground])
            assertEquals(actual[SharedEmittedForeground], actual[SharedEmittedIcon])
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Custom Error") and
                    hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true,
            ).assertCountEquals(1)[0].assertIsDisplayed()
            compose.onAllNodes(
                hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true,
            ).assertCountEquals(0)
            assertEquals(0, disconnectedTaps)
        }
        assertOriginalFailure()
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("custom-failure:1").assertExists()
        assertOriginalFailure()
    }
}
