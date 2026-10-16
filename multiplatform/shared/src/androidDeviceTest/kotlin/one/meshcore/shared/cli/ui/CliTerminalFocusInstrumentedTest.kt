package one.meshcore.shared.cli.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliTerminalFocusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val waiting = mutableStateOf(false)
    private lateinit var view: android.view.View

    @After
    fun tearDown() {
        if (::view.isInitialized) {
            compose.runOnUiThread {
                WindowInsetsControllerCompat(compose.activity.window, view).hide(WindowInsetsCompat.Type.ime())
            }
        }
    }

    @Test
    fun defaultPeerTerminalStillRequestsInitialKeyboard() {
        show(autoFocusOnMount = true)
        compose.waitUntil(TIMEOUT_MS) { imeBottom() > 0 }
    }

    @Test
    fun mountedReplyRestoresFocusAfterInitialAutofocusWasSuppressed() {
        show(autoFocusOnMount = false)
        compose.waitForIdle()
        assertEquals(0, imeBottom())
        compose.runOnIdle { waiting.value = true }
        compose.waitForIdle()
        compose.runOnIdle { waiting.value = false }
        compose.waitUntil(TIMEOUT_MS) { imeBottom() > 0 }
    }

    private fun show(autoFocusOnMount: Boolean) {
        compose.runOnIdle { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                CliTerminalPane(
                    outputLines = emptyList(), promptText = "fixture> ", ghostText = "", tabSuggestions = null,
                    tabSelectionIndex = null, isWaitingForResponse = waiting.value, input = TextFieldValue("draft"),
                    onInputChange = {}, actions = ACTIONS, autoFocusOnMount = autoFocusOnMount,
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                )
            }
        }
    }

    private fun imeBottom(): Int = requireNotNull(ViewCompat.getRootWindowInsets(view))
        .getInsets(WindowInsetsCompat.Type.ime()).bottom

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val ACTIONS = CliTerminalActions(
            onSubmit = {}, onTab = {}, onHistoryUp = {}, onHistoryDown = {}, onAcceptGhost = {},
            onUpdateGhostText = {}, onSetInput = {}, onCancel = {}, onClear = {}, onCopyBlock = { it.text },
            onApplySuggestion = {},
        )
    }
}
