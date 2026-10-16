package one.meshcore.shared.room

import one.meshcore.shared.crypto.sha256
import one.meshcore.shared.model.toUppercaseHex

/**
 * Room-message deduplication key generation — ported byte-for-byte from the iOS
 * `RoomMessage.generateDeduplicationKey`. The key gates duplicate suppression, so it is computed
 * identically at message creation and at receive time: same author + same timestamp + same text
 * collapse to one row, letting a locally-created post and an unexpected server echo dedupe.
 *
 * Lives in a `room` package (not `channel`): it is a room primitive assigned to the channel-wire
 * slice but shares nothing with channel derivation. Later room slices build the persistence and
 * service layers on top of it.
 */
object RoomMessageDedup {
    /** Bytes of `SHA-256(text)` taken for the content-hash segment of the key. */
    private const val CONTENT_HASH_PREFIX_BYTES = 4

    /**
     * `"{timestamp}-{authorHexUpper}-{sha256(text)[0..4]HexUpper}"`. The timestamp is the decimal
     * UInt32 with no zero-padding; both hex segments are uppercase with no separators.
     */
    fun generateDeduplicationKey(timestamp: UInt, authorKeyPrefix: ByteArray, text: String): String {
        val authorHex = authorKeyPrefix.toUppercaseHex()
        val hashPrefix = sha256(text.encodeToByteArray()).copyOf(CONTENT_HASH_PREFIX_BYTES).toUppercaseHex()
        return "$timestamp-$authorHex-$hashPrefix"
    }
}
