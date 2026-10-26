// AndroidOnly: WP-313 Mounted Robolectric regression of retained Remote CLI tab composition; not device or TalkBack evidence.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private class Probe { var created = 0; var disposed = 0 }

    private var selected by mutableStateOf(false)
    private var routeVisible by mutableStateOf(true)
    private var sessionId by mutableStateOf("session-1")
    private var cliFocused by mutableStateOf(false)
    private val probe = Probe()

    private fun mount() {
        controller.get().setContent {
            if (routeVisible) Column {
                key(sessionId) {
                    RetainedTabContent(selected) {
                        val held = remember { probe.also { it.created++ } }
                        var terminal by remember { mutableStateOf("empty") }
                        DisposableEffect(held) { onDispose { held.disposed++ } }
                        Column {
                            Text("terminal", Modifier.testTag("cli-terminal"))
                            Text(terminal, Modifier.testTag("terminal-state"))
                            TextButton(onClick = { terminal = "typed state" }) { Text("Edit terminal") }
                            BasicTextField(
                                value = "",
                                onValueChange = {},
                                modifier = Modifier.onFocusChanged { cliFocused = it.isFocused }.testTag("cli-focus"),
                            )
                        }
                    }
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
        compose.onNodeWithText("Edit terminal").performClick()
        compose.onNodeWithText("typed state").assertIsDisplayed()
        compose.onNodeWithTag("cli-focus").performClick()
        compose.runOnIdle { assertEquals(true, cliFocused) }
        repeat(3) {
            compose.runOnIdle { selected = false }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(false, cliFocused) }
            compose.onNode(hasTestTag("cli-terminal")).assertDoesNotExistCompat()
            compose.onNodeWithText("other tab").assertIsDisplayed()
            compose.runOnIdle { selected = true }
            compose.onNodeWithTag("cli-terminal").assertIsDisplayed()
            compose.onNodeWithText("typed state").assertIsDisplayed()
            compose.onNodeWithTag("cli-focus").performClick()
            compose.runOnIdle { assertEquals(true, cliFocused) }
        }
        assertEquals(1, probe.created)
        assertEquals(0, probe.disposed)
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

    @Test fun changingSessionIdentityDisposesThePreviousTerminal() {
        mount()
        compose.runOnIdle { selected = true }
        compose.waitForIdle()
        assertEquals(1, probe.created)
        compose.runOnIdle { selected = false }
        compose.waitForIdle()
        assertEquals(0, probe.disposed)
        compose.runOnIdle { sessionId = "session-2" }
        compose.waitForIdle()
        assertEquals(1, probe.disposed)
        compose.runOnIdle { selected = true }
        compose.waitForIdle()
        assertEquals(2, probe.created)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() =
        assertDoesNotExist()
}
