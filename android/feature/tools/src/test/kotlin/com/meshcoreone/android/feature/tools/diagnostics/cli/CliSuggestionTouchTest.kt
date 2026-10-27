// AndroidOnly: WP-316 TS-30 Mount the production CLI and distinguish pointer choice from keyboard cycling.
package com.meshcoreone.android.feature.tools.diagnostics.cli

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsDependencies
import com.meshcoreone.android.feature.tools.diagnostics.ToolsDiagnosticsScreen
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogFeatureDependencies
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CliSuggestionTouchTest {
    private lateinit var activity: ActivityController<Activity>
    private lateinit var owner: ScreenOwner
    private lateinit var content: ComposeView
    private val admin = CliRepeaterAdminPort { _, _, _ -> error("Completion must not send commands") }
    private val dependencies = ToolsDiagnosticsDependencies(
        cli = CliToolFeatureDependencies(
            repeaterAdminService = { admin },
            remoteNodeService = { null },
            settingsService = { null },
            dataStore = { null },
            radioId = { null },
            connectedDevice = { null },
            sendSelfAdvert = { error("Completion must not advertise") },
        ),
        rxLog = RxLogFeatureDependencies({ null }, { null }, { null }),
        radioStats = { null },
        isConnected = { true },
        localPublicKeyPrefix = { null },
        connectionVersion = MutableStateFlow(0),
    )

    @Before fun mountScreen() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        activity.get().actionBar?.hide()
        activity.windowFocusChanged(true)
        owner = ScreenOwner()
        content = ComposeView(activity.get()).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { MaterialTheme { ToolsDiagnosticsScreen(dependencies) } }
        }
        activity.get().setContentView(content)
        advanceFrames()
    }

    @After fun disposeScreen() {
        content.disposeComposition()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        activity.windowFocusChanged(false)
        activity.pause().stop().destroy()
    }

    @Test fun touchChoosesClickedEntryBeforeAndAfterKeyboardSelectionAndWraparound() {
        for (tabs in 1..4) {
            for (clicked in listOf("login", "logout")) {
                setInput("lo")
                repeat(tabs) { pressTab() }
                tap(chip(clicked))
                assertCompleted("$clicked ")
            }
        }
    }

    @Test fun keyboardSelectionStillAppliesFirstNextAndWrappedEntryWithoutExecuting() {
        for ((tabs, expected) in listOf(2 to "login ", 3 to "logout ", 4 to "login ")) {
            setInput("lo")
            repeat(tabs) { pressTab() }
            val submit = assertNotNull(input().config[SemanticsActions.OnImeAction].action)
            assertTrue(submit())
            advanceFrames()
            assertCompleted(expected)
        }
    }

    @Test fun touchPreservesArgumentPrefixAndPlacesNextTypedTextAfterCompletion() {
        setInput("session l")
        repeat(3) { pressTab() }
        tap(chip("local"))
        assertCompleted("session local ")
        val insert = assertNotNull(input().config[SemanticsActions.InsertTextAtCursor].action)
        assertTrue(insert(AnnotatedString("x")))
        advanceFrames()
        assertEquals("session local x", input().config[SemanticsProperties.EditableText].text)
    }

    @Test @Config(qualifiers = "w840dp-h900dp-mdpi")
    fun expandedWindowAtDoubleFontStillChoosesClickedEntryAfterKeyboardSelection() {
        content.disposeComposition()
        content.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f),
            ) {
                MaterialTheme { ToolsDiagnosticsScreen(dependencies) }
            }
        }
        advanceFrames()
        setInput("lo")
        repeat(2) { pressTab() }
        tap(chip("logout"))
        assertCompleted("logout ")
    }

    private fun setInput(text: String) {
        assertTrue(assertNotNull(input().config[SemanticsActions.RequestFocus].action)())
        assertTrue(assertNotNull(input().config[SemanticsActions.SetText].action)(AnnotatedString(text)))
        advanceFrames()
    }

    private fun assertCompleted(expected: String) {
        val field = input().config
        assertEquals(expected, field[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(expected.length), field[SemanticsProperties.TextSelectionRange])
        assertTrue(field[SemanticsProperties.Focused])
        assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
            text.text == "login" || text.text == "logout" || text.text == "local" || text.text == "list"
        } })
    }

    private fun input(): SemanticsNode = nodes().single {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == "cli-input"
    }

    private fun chip(label: String): SemanticsNode = nodes().single {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.singleOrNull()?.text == label
    }

    private fun pressTab() {
        assertTrue(composeRoot().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)))
        composeRoot().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
        advanceFrames()
    }

    private fun tap(node: SemanticsNode) {
        val bounds = node.boundsInRoot
        assertTrue(bounds.width > 0 && bounds.height > 0)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, bounds.center.x, bounds.center.y, 0)
            try {
                assertTrue(composeRoot().dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        advanceFrames()
    }

    private fun nodes(): List<SemanticsNode> {
        val root = composeRoot()
        val semantics = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        return buildList {
            fun visit(node: SemanticsNode) {
                add(node)
                node.children.forEach(::visit)
            }
            visit(semantics.rootSemanticsNode)
        }
    }

    private fun composeRoot(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
            }
            return null
        }
        return assertNotNull(find(content), "Production Compose screen must be attached")
    }

    private fun advanceFrames() {
        repeat(6) {
            Snapshot.sendApplyNotifications()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        // Robolectric draws no frame, so newly composed chips stay unplaced (0x0) until layout is forced.
        val owner = composeRoot()
        owner.javaClass.methods.filter { it.name == "measureAndLayout" }.forEach { m ->
            if (m.parameterCount == 1) m.invoke(owner, true) else m.invoke(owner)
        }
    }

    private class ScreenOwner : SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        private val savedState = SavedStateRegistryController.create(this).apply {
            performAttach()
            performRestore(null)
        }
        override val savedStateRegistry get() = savedState.savedStateRegistry
        init { registry.currentState = Lifecycle.State.RESUMED }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CliSuggestionSelectionTest {
    @Test fun directChoiceReusesCompletionAndClearsSelectionAndGhostText() {
        val scheduler = TestScheduler()
        val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
        for (tabs in 1..4) {
            for (clicked in listOf("login", "logout")) {
                holder.updateInput("lo")
                holder.updateGhostText(true)
                repeat(tabs) { holder.tabComplete() }
                assertTrue(holder.applySuggestion(clicked))
                val terminal = holder.state.value.terminal
                assertEquals("$clicked ", terminal.currentInput)
                assertEquals("", terminal.ghostText)
                assertNull(terminal.tabSuggestions)
                assertNull(terminal.tabSelectionIndex)
                assertTrue(terminal.commandHistory.isEmpty())
                assertTrue(terminal.outputLines.isEmpty())
            }
        }
    }

    @Test fun dismissedOrUnknownSuggestionCannotMutateInputOrKeyboardSelection() {
        val scheduler = TestScheduler()
        val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
        holder.updateInput("lo")
        repeat(2) { holder.tabComplete() }
        val selected = holder.state.value
        assertFalse(holder.applySuggestion("not a suggestion"))
        assertEquals(selected, holder.state.value)
        holder.clearTabState()
        val dismissed = holder.state.value
        assertFalse(holder.applySuggestion("logout"))
        assertEquals(dismissed, holder.state.value)
    }
}
