package one.meshcore.shared.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `RegenerateIdentityViewModel` speaks public-key bytes as comma-separated hex pairs. */
class GeneratedIdentityKeyTalkBackTest {
    @Test
    fun publicKeySpeaksCommaSeparatedBytePairs() {
        assertEquals(
            "0A, FF",
            generatedPublicKeyAccessibilityLabel(byteArrayOf(0x0A, 0xFF.toByte())),
        )
        assertEquals("", generatedPublicKeyAccessibilityLabel(byteArrayOf()))
    }
}
