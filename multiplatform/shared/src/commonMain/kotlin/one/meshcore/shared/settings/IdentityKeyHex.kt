package one.meshcore.shared.settings

import one.meshcore.shared.model.decodeHex

/**
 * Live-filters the import field to uppercase ASCII hex, matching iOS `ImportKeyViewModel.sanitizeInput`.
 * Colons, dashes, spaces, and a leading `0x` drop out as the user types so a grouped paste can still
 * reach 128 digits.
 */
fun sanitizePrivateKeyHexInput(raw: String): String = raw.filter { it in HEX_DIGITS }.uppercase()

/** True when [raw] is 128 hex digits (optional 0x / whitespace), before Ed25519 clamp checks. */
fun isPrivateKeyHexLength(raw: String): Boolean {
    val cleaned = raw.trim().removePrefix("0x").removePrefix("0X").filterNot { it.isWhitespace() }
    return cleaned.length == PRIVATE_KEY_HEX_LENGTH && cleaned.all { it in HEX_DIGITS }
}

/**
 * Parses a MeshCore Ed25519 expanded private key from hex (optional 0x prefix, whitespace
 * ignored). The wire/export format is [KeyGenerationService.EXPANDED_PRIVATE_KEY_SIZE] bytes (a
 * 32-byte seed SHA-512-expanded to scalar + prefix, matching iOS `KeyGenerationService`'s 64-byte
 * format and `ProtocolLimits.privateKeySize`), not the 32-byte seed alone. Returns null when the
 * length, hex digits, or RFC 8032 scalar clamping are invalid.
 */
fun parsePrivateKeyHex(raw: String): ByteArray? {
    val cleaned = raw.trim().removePrefix("0x").removePrefix("0X").filterNot { it.isWhitespace() }
    if (cleaned.length != PRIVATE_KEY_HEX_LENGTH || !cleaned.all { it in HEX_DIGITS }) return null
    return decodeHex(cleaned)?.takeIf { KeyGenerationService.validateExpandedKey(it) }
}

private const val PRIVATE_KEY_BYTE_LENGTH = KeyGenerationService.EXPANDED_PRIVATE_KEY_SIZE
private const val PRIVATE_KEY_HEX_LENGTH = PRIVATE_KEY_BYTE_LENGTH * 2
private const val HEX_DIGITS = "0123456789abcdefABCDEF"
