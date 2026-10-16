package one.meshcore.shared.settings

/**
 * Validates a public-key vanity prefix for identity regeneration (1–4 hex chars; not 00/FF).
 * Mirrors iOS regenerate-identity prefix rules.
 */
object VanityPrefix {
    const val MIN_LENGTH = 1
    const val MAX_LENGTH = 4

    sealed class Validation {
        data object Ok : Validation()
        data object InvalidLength : Validation()
        data object Reserved : Validation()
        data object InvalidHex : Validation()
    }

    /**
     * Live-filters the regenerate-identity field to uppercase ASCII hex, capped at [MAX_LENGTH].
     * Matches iOS `RegenerateIdentityViewModel.sanitizePrefix` so a Unicode digit cannot sit in the
     * field and then fail [validate] as [Validation.InvalidHex].
     */
    fun sanitize(raw: String): String = raw.filter { it in HEX_DIGITS }.uppercase().take(MAX_LENGTH)

    fun validate(raw: String): Validation {
        val cleaned = raw.trim()
        val upper = cleaned.uppercase()
        return when {
            cleaned.length !in MIN_LENGTH..MAX_LENGTH -> Validation.InvalidLength
            cleaned.any { it !in HEX_DIGITS } -> Validation.InvalidHex
            // Firmware reserves public keys whose first byte is 0x00 or 0xFF (iOS KeyGenerationService).
            upper.length >= 2 && (upper.startsWith("00") || upper.startsWith("FF")) -> Validation.Reserved
            else -> Validation.Ok
        }
    }

    fun matchesPrefix(publicKeyHex: String, prefix: String): Boolean {
        val cleaned = prefix.trim()
        if (cleaned.isEmpty()) return true
        return publicKeyHex.startsWith(cleaned, ignoreCase = true)
    }

    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
}
