package one.meshcore.shared.tools

/**
 * Decryption outcome for channel and direct messages. Raw values match Swift `DecryptStatus`.
 */
enum class DecryptStatus(val raw: Int) {
    NOT_APPLICABLE(0),
    NO_MATCHING_KEY(1),
    HMAC_FAILED(2),
    DECRYPT_FAILED(3),
    SUCCESS(4),
    PENDING(5),
    DM_NO_MATCHING_KEY(6),
    ;

    companion object {
        fun fromRaw(raw: Int): DecryptStatus = entries.firstOrNull { it.raw == raw } ?: NOT_APPLICABLE
    }

    /** True for decrypt-filter "Failed" (channel or DM key/MAC/AES failure). */
    val isFailed: Boolean
        get() = this == NO_MATCHING_KEY ||
            this == HMAC_FAILED ||
            this == DECRYPT_FAILED ||
            this == DM_NO_MATCHING_KEY
}
