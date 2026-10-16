package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/**
 * Mention candidates heard on a channel. [senders] holds one synthetic [ContactDto] per distinct sender
 * name so a channel participant who is not a saved contact can still be `@`-mentioned; [order] carries the
 * latest wire timestamp seen for *every* sender name (saved contacts included) so
 * [one.meshcore.shared.chat.text.MentionUtilities.filterContacts] ranks the most recently heard first.
 * Port of the Swift `ChatViewModel.channelSenders` / `channelSenderOrder` pair.
 *
 * The caller merging [senders] into its contact list drops the names it already has; the index keeps them
 * so a rebuild never depends on when the contact book finished loading.
 */
data class ChannelSenderIndex(val senders: List<ContactDto> = emptyList(), val order: Map<String, UInt> = emptyMap()) {
    /**
     * Folds one live arrival in, max-merging its timestamp so an out-of-order row still contributes to
     * recency, and appending a synthetic contact only for a name not already tracked. Mirrors
     * `addChannelSenderIfNew`.
     */
    fun adding(senderName: String?, radioID: Uuid, timestamp: Long): ChannelSenderIndex {
        val name = normalizedSenderName(senderName) ?: return this
        val merged = order + (name to maxOf(timestamp.toUInt(), order[name] ?: 0u))
        if (senders.any { it.name == name }) return copy(order = merged)
        return ChannelSenderIndex(senders = senders + syntheticSenderContact(name, radioID), order = merged)
    }

    companion object {
        val EMPTY = ChannelSenderIndex()

        /** Longest sender name admitted as a mention candidate, matching the Swift 128-character guard. */
        private const val MAX_SENDER_NAME_LENGTH = 128

        /** Rebuilds the whole index from a loaded page. Mirrors `buildChannelSenders`. */
        fun from(messages: List<MessageDto>, radioID: Uuid): ChannelSenderIndex =
            messages.fold(EMPTY) { index, message ->
                index.adding(message.senderNodeName, radioID, message.timestamp)
            }

        private fun normalizedSenderName(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed.length > MAX_SENDER_NAME_LENGTH) return null
            return trimmed
        }

        /**
         * A contact-shaped mention candidate for a sender with no contact row: flood-routed, keyless, and
         * never persisted. Its id is derived from the name so the list key is stable across rebuilds.
         * Mirrors `makeSyntheticContact`.
         */
        private fun syntheticSenderContact(name: String, radioID: Uuid): ContactDto = ContactDto(
            id = stableUuid(name),
            radioID = radioID,
            publicKey = ByteArray(0),
            name = name,
            typeRawValue = ContactType.CHAT.raw,
            flags = 0,
            outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
            outPath = ByteArray(0),
            lastAdvertTimestamp = 0,
            latitude = 0.0,
            longitude = 0.0,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = false,
            unreadCount = 0,
        )
    }
}

/**
 * A deterministic UUID for [text], built from two djb2 streams over the even and odd UTF-8 bytes.
 * Non-cryptographic and never persisted — it only has to be stable and collision-free enough for list
 * identity. Byte-for-byte the Swift `String.stableUUID`.
 */
internal fun stableUuid(text: String): Uuid {
    var evenHash = DJB2_SEED
    var oddHash = DJB2_SEED
    text.encodeToByteArray().forEachIndexed { index, byte ->
        val value = (byte.toLong() and BYTE_MASK)
        if (index % 2 == 0) {
            evenHash = (evenHash shl DJB2_SHIFT) + evenHash + value
        } else {
            oddHash = (oddHash shl DJB2_SHIFT) + oddHash + value
        }
    }
    val bytes = ByteArray(UUID_BYTES) { index ->
        val source = if (index < UUID_BYTES / 2) evenHash else oddHash
        ((source ushr ((index % (UUID_BYTES / 2)) * BITS_PER_BYTE)) and BYTE_MASK).toByte()
    }
    return Uuid.fromBytes(bytes)
}

private const val DJB2_SEED = 5381L
private const val DJB2_SHIFT = 5
private const val BYTE_MASK = 0xFFL
private const val BITS_PER_BYTE = 8
private const val UUID_BYTES = 16
