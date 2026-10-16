package one.meshcore.shared.tools.ui

import one.meshcore.shared.cli.ui.cliTabSuggestionIsSelected
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTabSuggestionChipTest {
    @Test
    fun selectedIndexIsMarkedSelected() {
        assertTrue(cliTabSuggestionIsSelected(index = 2, selectedIndex = 2))
        assertFalse(cliTabSuggestionIsSelected(index = 1, selectedIndex = 2))
        assertFalse(cliTabSuggestionIsSelected(index = 0, selectedIndex = null))
    }
}
