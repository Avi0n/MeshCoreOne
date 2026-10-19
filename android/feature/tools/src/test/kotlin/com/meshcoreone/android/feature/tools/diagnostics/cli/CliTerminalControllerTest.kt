// AndroidOnly: WP-316 Native keyboard/cursor/accessory-bar state cases for the shared terminal controller.
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

class CliTerminalControllerTest {
    private val scheduler = TestScheduler()
    private val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
    private val controller = CliTerminalController(holder, scheduler.scope, scheduler.clock) { holder.executeCommand("session list") }
    private val terminal get() = holder.state.value.terminal
    private val cursor get() = controller.state.value.cursorPosition

    private fun type(text: String) = controller.onTextChanged(text, text.length)

    @Test
    fun `appearing focuses the keyboard and restores the cursor to the end`() {
        holder.updateInput("ver")
        controller.onAppear()
        assertTrue(controller.state.value.isKeyboardFocused)
        assertEquals(3, cursor)
        controller.onDisappear()
        assertFalse(controller.state.value.isKeyboardFocused)
    }

    @Test
    fun `typing refreshes ghost text and right arrow at the end accepts it`() {
        type("hel")
        assertEquals("p", terminal.ghostText)
        controller.onRightArrowAtEnd()
        assertEquals("help", terminal.currentInput)
        assertEquals(4, cursor)
        assertEquals("", terminal.ghostText)
    }

    @Test
    fun `moving the cursor off the end hides ghost text and move right walks back`() {
        type("hel")
        controller.onMoveLeft()
        assertEquals(2, cursor)
        assertEquals("", terminal.ghostText)
        assertEquals("he", controller.textBeforeCursor)
        assertEquals("l", controller.textAfterCursor)
        controller.onMoveRight()
        assertEquals(3, cursor)
        assertEquals("p", terminal.ghostText)
        controller.onMoveRight()
        assertEquals("help", terminal.currentInput)
    }

    @Test
    fun `cursor moves never split a surrogate pair`() {
        type("a😀")
        controller.onMoveLeft()
        assertEquals(1, cursor)
        controller.onMoveRight()
        assertEquals(3, cursor)
        controller.onMoveLeft()
        controller.onMoveLeft()
        controller.onMoveLeft()
        assertEquals(0, cursor)
    }

    @Test
    fun `a newline in the field submits the text before it`() {
        controller.onTextChanged("help\nignored", 12)
        scheduler.runCurrent()
        assertEquals("", terminal.currentInput)
        assertTrue(terminal.outputLines.any { it.text == "Available commands:" })
        assertEquals(0, cursor)
    }

    @Test
    fun `return applies a selected tab suggestion instead of executing`() {
        type("lo")
        controller.onTabComplete()
        controller.onTabComplete()
        assertEquals(0, terminal.tabSelectionIndex)
        controller.onTextChanged("lo\n", 3)
        assertEquals("login ", terminal.currentInput)
        assertEquals(6, cursor)
        assertTrue(terminal.outputLines.isEmpty())
    }

    @Test
    fun `editing the input clears tab selection`() {
        type("lo")
        controller.onTabComplete()
        assertTrue(terminal.tabSuggestions != null)
        type("log")
        assertNull(terminal.tabSuggestions)
    }

    @Test
    fun `escape clears a selection, then cancels a wait, then dismisses the keyboard`() {
        val gate = CompletableDeferred<Unit>()
        holder.configure(dependencies(sendSelfAdvert = { gate.await() }), localDeviceName = "Base")
        holder.setActiveSession(CliSession.local("Base"))
        controller.onAppear()
        type("lo")
        controller.onTabComplete()
        controller.onTabComplete()
        assertTrue(controller.onKey(CliTerminalKey.ESCAPE))
        assertNull(terminal.tabSelectionIndex)

        type("advert")
        controller.onSubmit()
        scheduler.runCurrent()
        assertTrue(holder.isWaitingForResponse)
        controller.onKey(CliTerminalKey.ESCAPE)
        assertFalse(holder.isWaitingForResponse)
        assertTrue(controller.state.value.isKeyboardFocused)

        controller.onKey(CliTerminalKey.ESCAPE)
        assertFalse(controller.state.value.isKeyboardFocused)
    }

    @Test
    fun `arrow keys walk history and command-K clears`() {
        holder.executeCommand("first")
        scheduler.runCurrent()
        assertTrue(controller.onKey(CliTerminalKey.UP))
        assertEquals("first", terminal.currentInput)
        assertEquals(5, cursor)
        controller.onKey(CliTerminalKey.DOWN)
        assertEquals("", terminal.currentInput)
        assertFalse(controller.onKey(CliTerminalKey.K))
        assertTrue(controller.onKey(CliTerminalKey.K, commandModifier = true))
        scheduler.runCurrent()
        assertTrue(terminal.outputLines.isEmpty())
    }

    @Test
    fun `paste inserts at the cursor and advances past the clipboard`() {
        type("get ")
        controller.onSelectionChanged(0)
        controller.onPaste("x ")
        assertEquals("x get ", terminal.currentInput)
        assertEquals(2, cursor)
        controller.onPaste(null)
        assertEquals("x get ", terminal.currentInput)
    }

    @Test
    fun `cancel button enables only after the reveal delay`() {
        controller.onWaitingChanged(true)
        scheduler.advanceBy(149.milliseconds)
        assertFalse(controller.state.value.showCancel)
        scheduler.advanceBy(1.milliseconds)
        assertTrue(controller.state.value.showCancel)
        controller.onWaitingChanged(false)
        assertFalse(controller.state.value.showCancel)
        controller.onWaitingChanged(true)
        controller.onWaitingChanged(false)
        scheduler.advanceBy(1.milliseconds.times(500))
        assertFalse(controller.state.value.showCancel)
    }

    @Test
    fun `the sessions button runs session list only when wired`() {
        assertTrue(controller.showsSessionsButton)
        controller.onSessions()
        scheduler.runCurrent()
        assertTrue(terminal.commandHistory.contains("session list"))
        assertFalse(CliTerminalController(holder, scheduler.scope, scheduler.clock).showsSessionsButton)
    }
}
