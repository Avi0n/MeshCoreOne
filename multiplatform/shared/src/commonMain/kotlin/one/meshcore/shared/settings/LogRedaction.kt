package one.meshcore.shared.settings

import one.meshcore.shared.model.toLowercaseHex

/**
 * Centralized redaction for logging sensitive data, a verbatim port of the Swift `LogRedaction`. These rules
 * gate what reaches the exported diagnostics log, so they are security-critical and frozen: public keys are
 * shortened (not redacted — they are public), node/room names are partially masked, and CLI passwords are
 * dropped entirely.
 */
object LogRedaction {
    /** Placeholder emitted in place of a password value that must never be logged. */
    const val PASSWORD_PLACEHOLDER: String = "[REDACTED]"

    private const val PUBLIC_KEY_PREFIX_BYTES = 6
    private const val NODE_NAME_VISIBLE_CHARS = 3
    private const val CLI_TRUNCATE_LENGTH = 40

    /**
     * Lowercase hex of the first [prefixLength] bytes of [key]. Public keys are NOT redacted (they are
     * public); this only shortens them for readability.
     */
    fun publicKeyHex(key: ByteArray, prefixLength: Int = PUBLIC_KEY_PREFIX_BYTES): String =
        key.take(prefixLength).toByteArray().toLowercaseHex()

    /** A node/room name masked to its first [NODE_NAME_VISIBLE_CHARS] chars + `***`, or all-`***` if short. */
    fun nodeName(name: String): String =
        if (name.length <= NODE_NAME_VISIBLE_CHARS) "***" else name.take(NODE_NAME_VISIBLE_CHARS) + "***"

    /**
     * Redacts the value from a `password …` / `set password …` CLI command (keeping the command words and
     * appending [PASSWORD_PLACEHOLDER]); otherwise truncates a long command to [CLI_TRUNCATE_LENGTH] chars.
     */
    fun cliCommand(command: String): String {
        val lower = command.lowercase()
        if (lower.startsWith("password") || lower.contains("set password")) {
            val parts = command.split(" ", limit = 3)
            if (parts.size >= 2) {
                return parts.dropLast(1).joinToString(" ") + " $PASSWORD_PLACEHOLDER"
            }
        }
        return if (command.length <= CLI_TRUNCATE_LENGTH) command else command.take(CLI_TRUNCATE_LENGTH) + "..."
    }
}
