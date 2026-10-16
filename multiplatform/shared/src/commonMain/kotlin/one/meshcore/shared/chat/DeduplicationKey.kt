package one.meshcore.shared.chat

import one.meshcore.shared.crypto.sha256
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/**
 * Single source of truth for content-based deduplication keys. Content-based (not the RX-log per-packet
 * hash) because retries with different attempt counters produce different packet hashes but identical
 * content, and dedup must survive them. Mirrors Swift `DeduplicationKey.contentBased`.
 */
object DeduplicationKey {
    const val CHANNEL_PREFIX = "ch-"
    const val DIRECT_MESSAGE_PREFIX = "dm-"
    const val UNKNOWN_CONTACT_PLACEHOLDER = "unknown"

    private const val HASH_PREFIX_BYTES = 4

    /**
     * Builds the stable dedup key. The hash prefix is the first [HASH_PREFIX_BYTES] bytes of
     * `SHA-256(utf8(content))` as uppercase hex (8 chars). Channel keys carry the channel index,
     * timestamp, sender node name, and hash; DM keys carry the contact id (or [UNKNOWN_CONTACT_PLACEHOLDER]),
     * timestamp, and hash.
     */
    fun contentBased(
        contactID: Uuid?,
        channelIndex: Int?,
        senderNodeName: String?,
        timestamp: Long,
        content: String,
    ): String {
        val hashPrefix = sha256(content.encodeToByteArray()).copyOf(HASH_PREFIX_BYTES).toUppercaseHex()
        if (channelIndex != null) {
            return "$CHANNEL_PREFIX$channelIndex-$timestamp-${senderNodeName ?: ""}-$hashPrefix"
        }
        val contactSegment = contactID?.value ?: UNKNOWN_CONTACT_PLACEHOLDER
        return "$DIRECT_MESSAGE_PREFIX$contactSegment-$timestamp-$hashPrefix"
    }
}
