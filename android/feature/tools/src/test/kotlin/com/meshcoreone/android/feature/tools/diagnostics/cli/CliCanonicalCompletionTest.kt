// AndroidOnly: WP-316 Supplemental canonical-prefix, verbatim-text and real terminal-consumer regressions for issue196.
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CliCanonicalCompletionTest {
    @Test
    fun learnedLoginPrefixUsesCanonicalEquivalence() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9"))
        assertEquals(listOf("\u00e9"), engine.completions("login e\u0301", isLocal = true))
        assertEquals(listOf("\u00e9"), engine.nodeNames)
    }

    @Test
    fun learnedCustomKeyPrefixUsesCanonicalEquivalence() {
        val engine = CliCompletionEngine()
        engine.updateCustomVarKeys(listOf("\u00e9"))
        assertEquals(listOf("\u00e9"), engine.completions("get e\u0301", isLocal = true))
        assertEquals(listOf("\u00e9"), engine.completions("set e\u0301", isLocal = true))
        assertEquals(listOf("\u00e9"), engine.customVarKeys)
    }

    @Test
    fun storedSuggestionProvidesGhostSuffix() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node"))
        val suggestions = engine.completions("login ", isLocal = true)
        assertEquals(listOf("\u00e9Node"), suggestions)
        val state = CliTerminalState(currentInput = "login e\u0301").withGhostText(suggestions.single())
        assertEquals("Node", state.ghostText)
        assertEquals("login e\u0301", state.currentInput)
    }

    @Test
    fun tabCompletionUsesVerbatimLearnedNode() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node"))
        val (state, suggestions) = CliTerminalState(currentInput = "login e\u0301").tabCompleting {
            engine.completions(it, isLocal = true)
        }
        assertEquals("login \u00e9Node ", state.currentInput)
        assertEquals("", state.ghostText)
        assertNull(suggestions)
    }

    @Test
    fun acceptingGhostKeepsOriginalDecomposedInput() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node"))
        val input = "login e\u0301"
        val suggestions = engine.completions(input, isLocal = true)
        assertEquals(listOf("\u00e9Node"), suggestions)
        val state = CliTerminalState(currentInput = input).withGhostText(suggestions.single()).acceptingGhostText()
        assertEquals("login e\u0301Node", state.currentInput)
        assertEquals("", state.ghostText)
    }

    @Test
    fun loginAndSessionPreserveDecomposedLearnedName() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("e\u0301Node"))
        for (command in listOf("login", "session")) {
            val input = "$command \u00e9"
            val suggestions = engine.completions(input, isLocal = true)
            assertEquals(listOf("e\u0301Node"), suggestions)
            assertEquals("Node", CliTerminalState(currentInput = input).withGhostText(suggestions.single()).ghostText)
            val (completed, _) = CliTerminalState(currentInput = input).tabCompleting {
                engine.completions(it, isLocal = true)
            }
            assertEquals("$command e\u0301Node ", completed.currentInput)
        }
        assertEquals(listOf("e\u0301Node"), engine.nodeNames)
    }

    @Test
    fun canonicalCustomDuplicatesKeepFirstVerbatimSpellingAndCase() {
        val engine = CliCompletionEngine()
        for (keys in listOf(
            listOf("\u00e9", "e\u0301", "\u00c9"),
            listOf("e\u0301", "\u00e9", "\u00c9"),
        )) {
            engine.updateCustomVarKeys(keys)
            for (command in listOf("get", "set")) {
                assertEquals(listOf("\u00c9", keys.first()), engine.completions("$command e\u0301", isLocal = true))
            }
            assertEquals(keys, engine.customVarKeys)
        }
    }

    @Test
    fun canonicalComparisonDoesNotApplyCompatibilityOrAccentFolding() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node", "\uff21Node", "ENode"))
        assertEquals(listOf("ENode"), engine.completions("login e", isLocal = true))
        assertTrue(engine.completions("login a", isLocal = true).isEmpty())
    }

    @Test
    fun toolControllerPreservesInputAndVerbatimTabSuggestionWithoutExecutingCommands() {
        val scheduler = TestScheduler()
        val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
        holder.completionEngine.updateNodeNames(listOf("\u00e9Node"))
        val controller = CliTerminalController(holder, scheduler.scope, scheduler.clock) {}
        val input = "login e\u0301"

        controller.onTextChanged(input, input.length)
        assertEquals("Node", holder.ghostText)
        controller.onRightArrowAtEnd()
        assertEquals("login e\u0301Node", holder.currentInput)
        assertEquals(holder.currentInput.length, controller.state.value.cursorPosition)

        controller.onTextChanged(input, input.length)
        controller.onSelectionChanged(0)
        assertEquals("", holder.ghostText)
        assertEquals(input, holder.currentInput)
        controller.onSelectionChanged(input.length)
        assertEquals("Node", holder.ghostText)
        controller.onTabComplete()
        assertEquals("login \u00e9Node ", holder.currentInput)
        assertEquals(holder.currentInput.length, controller.state.value.cursorPosition)
        assertEquals(listOf("\u00e9Node"), holder.completionEngine.nodeNames)
        assertTrue(holder.state.value.terminal.commandHistory.isEmpty())
        assertTrue(holder.state.value.terminal.outputLines.isEmpty())
        assertFalse(holder.isWaitingForResponse)
        holder.cleanup()
    }
}
