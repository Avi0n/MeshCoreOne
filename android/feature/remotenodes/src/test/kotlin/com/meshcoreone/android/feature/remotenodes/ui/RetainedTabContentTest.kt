// AndroidOnly: WP-313 Mounted Robolectric regression of retained Remote CLI tab composition; not device or TalkBack evidence.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w360dp-h800dp")
class RetainedTabContentTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>

    @Before fun openWindow() { controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible() }
    @After fun closeWindow() { controller.pause().stop().destroy() }

    private class Probe { var created = 0; var disposed = 0; var terminal = "" }

    private var selected by mutableStateOf(false)
    private var routeVisible by mutableStateOf(true)
    private val probe = Probe()

    private fun mount() {
        controller.get().setContent {
            if (routeVisible) Column {
                RetainedTabContent(selected) {
                    val held = remember { probe.also { it.created++ } }
                    DisposableEffect(held) { onDispose { held.disposed++ } }
                    Text("terminal", Modifier.testTag("cli-terminal"))
                }
                Text("other tab", Modifier.testTag("other-tab"))
            }
        }
        compose.waitForIdle()
    }

    @Test fun unvisitedTabIsNotComposed() {
        mount()
        compose.onNode(hasTestTag("cli-terminal")).assertDoesNotExistCompat()
        assertEquals(0, probe.created)
    }

    @Test fun visitedTabKeepsOneHolderAcrossTabChangesAndIsHiddenWhileUnselected() {
        mount()
        compose.runOnIdle { selected = true }
        compose.onNodeWithTag("cli-terminal").assertIsDisplayed()
        compose.runOnIdle { probe.terminal = "typed state" }
        repeat(3) {
            compose.runOnIdle { selected = false }
            compose.waitForIdle()
            compose.onNode(hasTestTag("cli-terminal")).assertDoesNotExistCompat()
            compose.onNodeWithText("other tab").assertIsDisplayed()
            compose.runOnIdle { selected = true }
            compose.onNodeWithTag("cli-terminal").assertIsDisplayed()
        }
        assertEquals(1, probe.created)
        assertEquals(0, probe.disposed)
        assertEquals("typed state", probe.terminal)
    }

    @Test fun holderIsDisposedOnlyWhenTheRouteLeavesComposition() {
        mount()
        compose.runOnIdle { selected = true }
        compose.runOnIdle { selected = false }
        compose.waitForIdle()
        assertEquals(0, probe.disposed)
        compose.runOnIdle { routeVisible = false }
        compose.waitForIdle()
        assertEquals(1, probe.disposed)
        assertEquals(1, probe.created)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() =
        assertDoesNotExist()
}
