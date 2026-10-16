package one.meshcore.shared.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusPillChromeTest {
    @Test
    fun failedFillAlphaStaysSubtle() {
        assertEquals(0.15f, FAILED_PILL_FILL_ALPHA)
    }

    @Test
    fun readySignalStaysTheOutdoorGreen() {
        assertEquals(Color(0xFF2E7D32), SAFETY_READY_GREEN)
    }
}
