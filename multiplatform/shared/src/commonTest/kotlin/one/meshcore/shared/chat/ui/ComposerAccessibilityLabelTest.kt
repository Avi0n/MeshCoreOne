package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ComposerAccessibilityLabelTest {
    @Test
    fun encryptionStatusStaysOnTheLabel() {
        val message = "Meet at the bridge"
        val encrypted = composerAccessibilityLabel("Message input", "Encrypted")
        assertEquals("Message input, Encrypted", encrypted)
        assertFalse(encrypted.contains(message))

        val open = composerAccessibilityLabel("Message input", "Not encrypted")
        assertEquals("Message input, Not encrypted", open)
        assertFalse(open.contains(message))
    }
}
