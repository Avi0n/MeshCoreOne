package one.meshcore.shared.chat

/**
 * The observable input state of the chat composer: the current [text] plus the derived byte-budget and
 * cooldown flags a send button reads. Immutable; [ComposerViewModel] emits a new value on every change.
 *
 * The mesh message limit is a UTF-8 **byte** budget, not a character count, because the firmware buffer
 * is byte-sized — one multi-byte emoji costs its bytes. [maxBytes] is the effective budget the caller
 * supplies (150 for a DM; the channel total minus the "NodeName: " header for a channel), so this state
 * stays agnostic to which kind of conversation hosts it.
 */
data class ComposerState(
    val text: String = "",
    val maxBytes: Int,
    val isCoolingDown: Boolean = false,
    /** Incremented to raise the composer once; 0 means no request. */
    val focusRequest: Int = 0,
) {
    /** UTF-8 byte length of [text] — the budget the firmware buffer is measured in. */
    val byteCount: Int get() = text.encodeToByteArray().size

    /** True when [text] exceeds [maxBytes] and cannot be sent. */
    val isOverLimit: Boolean get() = byteCount > maxBytes

    /** True once the byte count is within [COUNTER_VISIBILITY_THRESHOLD_BYTES] of the limit, or over it. */
    val shouldShowCharacterCount: Boolean get() = byteCount >= maxBytes - COUNTER_VISIBILITY_THRESHOLD_BYTES

    /**
     * Whether a send is currently allowed: the radio is [isReady], no post-send cooldown is active, the
     * trimmed text is non-empty, and the byte budget is not exceeded. Readiness is supplied by the caller
     * rather than held in state, mirroring the iOS input bar reading the live connection state in the view.
     */
    fun canSend(isReady: Boolean): Boolean = isReady && !isCoolingDown && text.trim().isNotEmpty() && !isOverLimit

    companion object {
        /** Show the byte counter once within this many bytes of the limit (or over it). */
        const val COUNTER_VISIBILITY_THRESHOLD_BYTES = 20
    }
}
