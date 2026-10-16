package one.meshcore.shared.ui

import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.navigation.selectTab
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_thread_back_accessibility
import one.meshcore.shared.resources.tools_cli_command_prompt
import one.meshcore.shared.tools.ToolsCliTerminalHost
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolsCliKeyboardInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigation = NavigationCoordinator(scope)
    private var room: NodeRemovalFixture? = null
    private lateinit var shell: AdaptiveShellFixture
    private lateinit var view: android.view.View
    private lateinit var promptLabel: String
    private lateinit var backLabel: String

    @After
    fun tearDown() {
        if (::view.isInitialized) compose.runOnUiThread { keyboard().hide(WindowInsetsCompat.Type.ime()) }
        ToolsCliTerminalHost.reset()
        scope.cancel()
        room?.close()
    }

    @Test
    fun railTabLeavesKeyboardClosedAndRetainsDraftOnReturn() {
        show(AdaptiveShellFixture.MEDIUM_WIDTH)
        openKeyboardAndType()
        tapTab(AppTab.CHATS)
        assertKeyboardClosed()
        tapTab(AppTab.TOOLS)
        compose.onNodeWithText(DRAFT).assertIsDisplayed()
        assertKeyboardClosed()
        openKeyboard()
    }

    @Test
    fun activeWideToolsTabCollapsesTerminalAndDismissesKeyboard() {
        show(AdaptiveShellFixture.WIDE_WIDTH)
        openKeyboardAndType()
        tapTab(AppTab.TOOLS)
        compose.runOnIdle { assertEquals(null, navigation.selectedTool.value) }
        assertKeyboardClosed()
    }

    @Test
    fun widthRemountKeepsDismissedKeyboardClosedAndDraftVisible() {
        show(AdaptiveShellFixture.MEDIUM_WIDTH)
        openKeyboardAndType()
        back()
        assertKeyboardClosed()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.WIDE_WIDTH }
        compose.waitForIdle()
        compose.onNodeWithText(DRAFT).assertIsDisplayed()
        assertKeyboardClosed()
        compose.runOnIdle { shell.widthDp = AdaptiveShellFixture.COMPACT_WIDTH }
        compose.waitForIdle()
        compose.onNodeWithText(DRAFT).assertIsDisplayed()
        assertKeyboardClosed()
        openKeyboard()
    }

    @Test
    fun compactToolbarUpDismissesKeyboardAndFreshOpenKeepsDraft() {
        show(AdaptiveShellFixture.COMPACT_WIDTH)
        openKeyboardAndType()
        compose.onNodeWithContentDescription(backLabel).performClick()
        compose.runOnIdle { assertEquals(null, navigation.selectedTool.value) }
        assertKeyboardClosed()
        compose.runOnIdle { navigation.selectTool(ToolSelection.CLI) }
        compose.onNodeWithText(DRAFT).assertIsDisplayed()
        compose.waitUntil(TIMEOUT_MS) { imeBottom() > 0 }
    }

    @Test
    fun nativeBackDismissesKeyboardBeforeLeavingTerminal() {
        show(AdaptiveShellFixture.COMPACT_WIDTH)
        openKeyboardAndType()
        back()
        assertKeyboardClosed()
        compose.runOnIdle { assertEquals(ToolSelection.CLI, navigation.selectedTool.value) }
        back()
        compose.waitUntil(TIMEOUT_MS) { navigation.selectedTool.value == null }
        assertKeyboardClosed()
    }

    private fun show(width: Float) {
        ToolsCliTerminalHost.reset()
        val room = NodeRemovalFixture(compose.activity, navigation).also { this.room = it }
        compose.runOnIdle {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            navigation.selectTab(AppTab.TOOLS)
            navigation.selectTool(ToolSelection.CLI)
        }
        shell =
            AdaptiveShellFixture(navigation, compose.activity.resources.displayMetrics.widthPixels, room.dependencies)
                .also { it.widthDp = width }
        compose.setContent {
            view = LocalView.current
            promptLabel = stringResource(Res.string.tools_cli_command_prompt)
            backLabel = stringResource(Res.string.chats_thread_back_accessibility)
            MaterialTheme { shell.Content() }
        }
        compose.onNodeWithContentDescription(promptLabel).assertIsDisplayed()
    }

    private fun openKeyboardAndType() {
        openKeyboard()
        InstrumentationRegistry.getInstrumentation().sendStringSync(DRAFT)
        compose.onNodeWithText(DRAFT).assertIsDisplayed()
        capturePayloadFrame("cli-keyboard-open.png")
    }

    private fun openKeyboard() {
        compose.onNodeWithContentDescription(promptLabel).performTouchInput { click() }
        compose.runOnUiThread { keyboard().show(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(TIMEOUT_MS) { imeBottom() > 0 }
        compose.waitForIdle()
    }

    private fun assertKeyboardClosed() {
        var stableSamples = 0
        compose.waitUntil(TIMEOUT_MS) {
            stableSamples = if (imeBottom() == 0) stableSamples + 1 else 0
            stableSamples >= STABLE_SAMPLES
        }
        compose.waitForIdle()
        assertEquals(0, imeBottom())
        capturePayloadFrame("cli-keyboard-closed.png")
    }

    private fun tapTab(tab: AppTab) {
        compose.onNode(hasText(shell.tabLabels.getValue(tab)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun keyboard() = WindowInsetsControllerCompat(compose.activity.window, view)

    private fun imeBottom(): Int = requireNotNull(ViewCompat.getRootWindowInsets(view))
        .getInsets(WindowInsetsCompat.Type.ime()).bottom

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val STABLE_SAMPLES = 3
        const val DRAFT = "draft"
    }
}
