package one.meshcore.shared.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ScanQrOverlayCaptionTest {
    @Test
    fun overlayFillAlphasStayReadableOnCamera() {
        assertEquals(0.8f, SCAN_QR_ERROR_FILL_ALPHA)
        assertEquals(0.6f, SCAN_QR_INSTRUCTION_FILL_ALPHA)
    }

    @Test
    fun instructionFillUsesInverseSurfaceNotHardcodedBlack() {
        val inverse = Color(0xFF1A1C1E)
        assertEquals(inverse.copy(alpha = SCAN_QR_INSTRUCTION_FILL_ALPHA), scanQrInstructionFill(inverse))
    }

    @Test
    fun errorFillUsesThemeErrorNotHardcodedRed() {
        val error = Color(0xFFB3261E)
        assertEquals(error.copy(alpha = SCAN_QR_ERROR_FILL_ALPHA), scanQrErrorFill(error))
    }
}
