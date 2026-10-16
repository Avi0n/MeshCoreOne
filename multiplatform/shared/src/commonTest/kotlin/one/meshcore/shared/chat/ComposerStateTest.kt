package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the pure gating on [ComposerState]: the UTF-8 byte budget at the boundary (a multi-byte emoji
 * costs its bytes, not one character), and the ready / cooldown / non-empty / within-budget conjunction of
 * `canSend`. Mirrors the send-gating half of the Swift `ChatInputBar`.
 */
class ComposerStateTest {
    private val emoji = "😀" // One grapheme, two UTF-16 code units, four UTF-8 bytes.

    @Test
    fun byteCountMeasuresUtf8BytesNotCharacters() {
        val state = ComposerState(text = emoji, maxBytes = 150)
        assertEquals(2, state.text.length) // A surrogate pair in Kotlin's UTF-16 String.
        assertEquals(4, state.byteCount) // The mesh budget is UTF-8 bytes, which the length undercounts.
    }

    @Test
    fun textExactlyAtTheByteLimitIsNotOverLimit() {
        val state = ComposerState(text = emoji, maxBytes = 4)
        assertFalse(state.isOverLimit)
        assertTrue(state.canSend(isReady = true))
    }

    @Test
    fun oneByteOverTheLimitBlocksSend() {
        val state = ComposerState(text = emoji + emoji, maxBytes = 4)
        assertEquals(8, state.byteCount)
        assertTrue(state.isOverLimit)
        assertFalse(state.canSend(isReady = true))
    }

    @Test
    fun readyGateBlocksSendWhenNotReady() {
        val state = ComposerState(text = "hello", maxBytes = 150)
        assertFalse(state.canSend(isReady = false))
        assertTrue(state.canSend(isReady = true))
    }

    @Test
    fun cooldownBlocksSend() {
        val state = ComposerState(text = "hello", maxBytes = 150, isCoolingDown = true)
        assertFalse(state.canSend(isReady = true))
    }

    @Test
    fun whitespaceOnlyTextCannotSend() {
        val state = ComposerState(text = "   \n ", maxBytes = 150)
        assertFalse(state.canSend(isReady = true))
    }

    @Test
    fun counterAppearsWithinTwentyBytesOfTheLimit() {
        assertFalse(ComposerState(text = "a".repeat(129), maxBytes = 150).shouldShowCharacterCount)
        assertTrue(ComposerState(text = "a".repeat(130), maxBytes = 150).shouldShowCharacterCount)
        assertTrue(ComposerState(text = "a".repeat(160), maxBytes = 150).shouldShowCharacterCount)
    }
}
