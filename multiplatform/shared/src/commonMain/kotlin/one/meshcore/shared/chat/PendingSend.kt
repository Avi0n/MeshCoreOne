package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Discriminator for the single-table pending-send queue row. Raw values are persisted; pin them.
 * Mirrors the Swift `PendingSend` kind discriminator.
 */
enum class PendingSendKind(val raw: Int) {
    DM(0),
    CHANNEL(1),
    ;

    companion object {
        fun fromRaw(raw: Int): PendingSendKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** The in-memory unit a send queue drains. Round-trips to and from [PendingSendDto]. */
sealed interface SendEnvelope {
    val messageID: Uuid
    val isResend: Boolean
}

/** A direct-message send. */
data class DirectMessageEnvelope(
    override val messageID: Uuid,
    val contactID: Uuid,
    override val isResend: Boolean = false,
) : SendEnvelope

/**
 * A channel send. [messageText], [messageTimestamp], and [localNodeName] are captured so channel
 * reaction indexing can key off them; they are meaningless for DMs.
 */
data class ChannelMessageEnvelope(
    override val messageID: Uuid,
    val channelIndex: Int,
    override val isResend: Boolean = false,
    val messageText: String = "",
    val messageTimestamp: Long = 0,
    val localNodeName: String? = null,
) : SendEnvelope

/**
 * The persisted send-queue row, single-table discriminated design (do not split into two tables). The
 * durable form survives process death so the queue resumes on restart. This is the pure DTO shape used by
 * the Room entity, DAO, and hydrate replay. Mirrors the Swift `PendingSend` `@Model` fields relevant to
 * conversion.
 */
data class PendingSendDto(
    val id: Uuid,
    val radioID: Uuid,
    val messageID: Uuid,
    val kind: PendingSendKind,
    val sequence: Int,
    val enqueuedAtEpochSeconds: Double,
    val contactID: Uuid? = null,
    val channelIndex: Int? = null,
    val isResend: Boolean = false,
    val messageText: String = "",
    val messageTimestamp: Long = 0,
    val localNodeName: String? = null,
    val attemptCount: Int? = null,
)

/**
 * DM envelope to its persisted row. `messageText` and `messageTimestamp` are stored as the sentinels
 * (empty / 0) because DM preserve-timestamp is keyed off `attemptCount`, not the row's captured text.
 */
fun DirectMessageEnvelope.toPendingSendDto(
    id: Uuid,
    radioID: Uuid,
    sequence: Int,
    enqueuedAtEpochSeconds: Double,
    attemptCount: Int? = null,
): PendingSendDto = PendingSendDto(
    id = id,
    radioID = radioID,
    messageID = messageID,
    kind = PendingSendKind.DM,
    sequence = sequence,
    enqueuedAtEpochSeconds = enqueuedAtEpochSeconds,
    contactID = contactID,
    isResend = isResend,
    attemptCount = attemptCount,
)

/** Channel envelope to its persisted row. Text, timestamp, and node name are stored verbatim. */
fun ChannelMessageEnvelope.toPendingSendDto(
    id: Uuid,
    radioID: Uuid,
    sequence: Int,
    enqueuedAtEpochSeconds: Double,
    attemptCount: Int? = null,
): PendingSendDto = PendingSendDto(
    id = id,
    radioID = radioID,
    messageID = messageID,
    kind = PendingSendKind.CHANNEL,
    sequence = sequence,
    enqueuedAtEpochSeconds = enqueuedAtEpochSeconds,
    channelIndex = channelIndex,
    isResend = isResend,
    messageText = messageText,
    messageTimestamp = messageTimestamp,
    localNodeName = localNodeName,
    attemptCount = attemptCount,
)

/** DM envelope, or null when the row is not a DM or is missing its contact (discriminator guard). */
fun PendingSendDto.toDirectMessageEnvelope(): DirectMessageEnvelope? {
    if (kind != PendingSendKind.DM) return null
    val contactID = contactID ?: return null
    return DirectMessageEnvelope(messageID = messageID, contactID = contactID, isResend = isResend)
}

/** Channel envelope, or null when the row is not a channel or is missing its index (discriminator guard). */
fun PendingSendDto.toChannelMessageEnvelope(): ChannelMessageEnvelope? {
    if (kind != PendingSendKind.CHANNEL) return null
    val channelIndex = channelIndex ?: return null
    return ChannelMessageEnvelope(
        messageID = messageID,
        channelIndex = channelIndex,
        isResend = isResend,
        messageText = messageText,
        messageTimestamp = messageTimestamp,
        localNodeName = localNodeName,
    )
}

/** The row's envelope by discriminator; null on a malformed row. Used by hydrate to skip mismatches. */
fun PendingSendDto.toEnvelope(): SendEnvelope? = when (kind) {
    PendingSendKind.DM -> toDirectMessageEnvelope()
    PendingSendKind.CHANNEL -> toChannelMessageEnvelope()
}
