// AndroidOnly: WP-501 retained Swift canonical-prefix vector through actual CLI engine and terminal consumers.
package com.meshcoreone.android.parity

import com.meshcoreone.android.feature.tools.diagnostics.cli.CliCompletionEngine
import com.meshcoreone.android.feature.tools.diagnostics.cli.CliTerminalState
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CliCompletionUnicodeAcceptanceTest {
    // WP-316 oracle.swift.txt/output.txt retain "\u00e9".hasPrefix("e\u0301") == true.
    @Test fun learnedNodePrefixRetainsTheIndependentSwiftCanonicalMatch() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9"))

        assertEquals(listOf("\u00e9"), engine.completions("login e\u0301", isLocal = true))
    }

    @Test fun learnedCustomKeyPrefixRetainsTheIndependentSwiftCanonicalMatch() {
        val engine = CliCompletionEngine()
        engine.updateCustomVarKeys(listOf("\u00e9"))

        assertEquals(listOf("\u00e9"), engine.completions("get e\u0301", isLocal = true))
    }

    @Test fun actualTerminalGhostRetainsTheCanonicalPrefixAndOriginalInput() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node"))
        val terminal = CliTerminalState(currentInput = "login e\u0301")
        val first = engine.completions("login ", isLocal = true).single()
        assertEquals("\u00e9Node", first)
        val completed = terminal.withGhostText(first)

        assertEquals("Node", completed.ghostText)
        assertEquals("login e\u0301Node", completed.acceptingGhostText().currentInput)
    }

    @Test fun actualTerminalTabAppliesTheVerbatimCanonicalPrefixMatch() {
        val engine = CliCompletionEngine()
        engine.updateNodeNames(listOf("\u00e9Node"))
        val terminal = CliTerminalState(currentInput = "login e\u0301")
        val (completed, suggestions) = terminal.tabCompleting {
            engine.completions(it, isLocal = true)
        }

        assertEquals("login \u00e9Node ", completed.currentInput)
        assertNull(suggestions)
        assertNull(completed.tabSelectionIndex)
    }
}
