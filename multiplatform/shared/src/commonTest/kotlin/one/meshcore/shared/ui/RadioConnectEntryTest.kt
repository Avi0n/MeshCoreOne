package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression guard for RAD-1: connect chrome must open the radio pair sheet, not navigate to Nodes.
 */
class RadioConnectEntryTest {
    @Test
    fun connectEntryIsConnectRadioSheet() {
        assertIs<ShellSheet.ConnectRadio>(RadioConnectEntry.sheet)
        assertEquals(ShellSheet.ConnectRadio, RadioConnectEntry.sheet)
    }

    @Test
    fun connectEntryIsNotATabSwitchMarker() {
        // Navigate-to-contacts is a tab switch; connect entry must remain a distinct sheet object.
        assertTrue(RadioConnectEntry.sheet is ShellSheet.ConnectRadio)
    }
}
