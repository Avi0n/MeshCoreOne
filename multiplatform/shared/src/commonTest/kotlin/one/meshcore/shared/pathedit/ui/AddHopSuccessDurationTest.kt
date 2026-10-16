package one.meshcore.shared.pathedit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AddHopSuccessDurationTest {
    @Test
    fun successCheckMatchesIosPickerRow() {
        assertEquals(1_500L, PathEditMetrics.ADD_HOP_SUCCESS_MS)
    }
}
