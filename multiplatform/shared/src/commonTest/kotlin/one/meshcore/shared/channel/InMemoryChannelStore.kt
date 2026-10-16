package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/**
 * In-memory [ChannelStore] holding rows in a map behind [InMemoryChannelParts.mutex]. The reference
 * implementation of the slot-keyed frame upsert and the local-only-field-preservation rule: both the
 * desktop/test backing and the behavior a future Room adapter must reproduce. A frame upsert on an occupied
 * slot rewrites only name+secret when the secret is unchanged; a different secret wipes the previous
 * occupant's messages and derived counters. Every other local-only field (flood scope, notification level,
 * pinned, enabled) survives a same-secret rename. Messages are modeled as a per-slot count so the
 * clear/delete cascade is observable without pulling in the chat message store, mirroring
 * [one.meshcore.shared.contact.InMemoryContactStore].
 *
 * [newId] mints the local row handle on insert; the data-partition `radioID` always arrives from the
 * caller, so no path here mints one.
 */
class InMemoryChannelStore internal constructor(private val parts: InMemoryChannelParts) :
    ChannelStore by parts.store {
    constructor(newId: () -> Uuid = Uuid::random) : this(InMemoryChannelParts(newId))

    /** Inserts a fully-formed row (including local-only fields) so upsert-preservation is testable. Test-only. */
    suspend fun seedChannel(dto: ChannelDto) = parts.seeds.seedChannel(dto)

    /** Seeds [count] message rows for a slot so the clear/delete cascade is observable. Test-only. */
    suspend fun seedMessages(channelIndex: Int, count: Int) = parts.seeds.seedMessages(channelIndex, count)

    /** The remaining message-row count for a slot. Test-only. */
    suspend fun remainingMessageCount(channelIndex: Int): Int = parts.seeds.remainingMessageCount(channelIndex)

    /** The slots whose messages were deleted, in call order. Test-only. */
    suspend fun deletedMessagesForSlots(): List<Int> = parts.seeds.deletedMessagesForSlots()
}
