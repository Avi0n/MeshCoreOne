package one.meshcore.shared.cli.ui

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliFieldSyncTest {
    @Test
    fun aMatchingFieldIsLeftAlone() {
        assertNull(rewrittenCliField(currentInput = "ping", fieldText = "ping"))
    }

    @Test
    fun aRewrittenFieldPutsTheCaretAtTheEnd() {
        val field = rewrittenCliField(currentInput = "reboot", fieldText = "pin")
        assertEquals("reboot", field?.text)
        assertEquals(TextRange(6), field?.selection)
    }

    @Test
    fun ghostTextFollowsOnlyACollapsedCaretAtTheEnd() {
        assertTrue(cliCursorAtEnd(collapsed = true, cursor = 4, length = 4))
        assertFalse(cliCursorAtEnd(collapsed = true, cursor = 2, length = 4))
        assertFalse(cliCursorAtEnd(collapsed = false, cursor = 4, length = 4))
    }
}
