package one.meshcore.shared.channel

import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES

private const val SCOPE_TYPE_DISABLED = "disabled"
private const val SCOPE_TYPE_REGION = "region"

/** Thrown when the device's read-back default flood scope disagrees with what was just written. */
class DefaultFloodScopeVerificationFailed(val expected: String?, val actual: String?) :
    Exception("Default flood scope verification failed: expected $expected, got $actual")

/**
 * Persists the device's default flood scope and verifies via a follow-up read, mirroring iOS
 * `SettingsService.setDefaultFloodScopeVerified`. Passing `null` clears the persisted scope; non-null
 * names are truncated to [MAX_REGION_NAME_BYTES] UTF-8 bytes before send so the displayed name and the
 * scope key derived from it agree on the same bytes.
 *
 * A region scope travels as its name: the 16-byte key is derived from that name below the bridge, so
 * the `scopeKey` argument carries nothing for this scope type.
 *
 * @return the verified name read back from the device.
 */
suspend fun setDefaultFloodScopeVerified(session: MeshSession, name: String?): String? {
    val expected = name?.trim()?.takeIf { it.isNotEmpty() }?.utf8Truncate(MAX_REGION_NAME_BYTES)
    if (expected != null) {
        session.setDefaultFloodScope(
            name = expected,
            scopeType = SCOPE_TYPE_REGION,
            scopeText = expected,
            scopeKey = ByteArray(0),
        )
    } else {
        session.setDefaultFloodScope(
            name = "",
            scopeType = SCOPE_TYPE_DISABLED,
            scopeText = "",
            scopeKey = ByteArray(0),
        )
    }

    val actual = session.getDefaultFloodScope()?.name
    if (actual != expected) {
        throw DefaultFloodScopeVerificationFailed(expected = expected, actual = actual)
    }
    return actual
}

/** Truncates to at most [maxBytes] UTF-8 bytes without splitting a multi-byte character. */
private fun String.utf8Truncate(maxBytes: Int): String {
    var bytes = encodeToByteArray()
    if (bytes.size <= maxBytes) return this
    var end = maxBytes
    // Back off until we're not mid-sequence (UTF-8 continuation bytes are 0b10xxxxxx).
    while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_TAG) end--
    bytes = bytes.copyOf(end)
    return bytes.decodeToString()
}

private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_TAG = 0x80
