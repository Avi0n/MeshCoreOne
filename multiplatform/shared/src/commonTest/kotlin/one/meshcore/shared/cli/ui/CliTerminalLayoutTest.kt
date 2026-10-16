package one.meshcore.shared.cli.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class CliTerminalLayoutTest {
    @Test
    fun thePromptFollowsOutputInsteadOfPinningToTheScreenBottom() {
        assertEquals(0, cliTerminalLastIndex(outputCount = 0, hasSuggestions = false))
        assertEquals(3, cliTerminalLastIndex(outputCount = 3, hasSuggestions = false))
        assertEquals(4, cliTerminalLastIndex(outputCount = 3, hasSuggestions = true))
    }

    @Test
    fun navigationInsetDoesNotStackAboveTheKeyboard() {
        assertEquals(0, cliTerminalNavigationInset(imeBottom = 900, navigationBottom = 120))
        assertEquals(120, cliTerminalNavigationInset(imeBottom = 0, navigationBottom = 120))
    }
}
