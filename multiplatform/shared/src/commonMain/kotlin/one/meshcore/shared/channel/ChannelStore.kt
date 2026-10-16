package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid

/**
 * The persistence seam [ChannelService] depends on: the per-radio channel reads/writes plus the
 * message-cascade the clear/delete paths need. The interface keeps the service independently testable
 * while [one.meshcore.shared.persistence.RoomChannelStore] provides the production adapter. Reproduces
 * the subset of the Swift `ChannelPersisting` the service calls.
 *
 * Slot-keying invariant: every channel row is keyed by `(radioID, index)`, both frozen once set. A frame
 * upsert ([saveChannel]/[batchSaveChannels]) rewrites only name+secret on an existing slot when the
 * secret is unchanged, preserving the local-only fields the wire frame does not carry. A different
 * secret is a different occupant: messages and derived counters are wiped first. `radioID` always
 * arrives from the caller's live `device.radioID`, never minted here.
 */
interface ChannelStore : ChannelLocalUpdates {
    /** All channels for a radio, sorted by slot index. */
    suspend fun fetchChannels(radioID: Uuid): List<ChannelDto>

    /** The `(radioID, index)` channel, or null. */
    suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto?

    /**
     * Upserts one channel from a device [info], matched by `(radioID, index)`. Returns the row's local id.
     * Insert seeds a fresh row with defaults. Update with the same secret rewrites only name+secret;
     * a different secret wipes the previous occupant's history first.
     */
    suspend fun saveChannel(radioID: Uuid, info: ChannelInfo): Uuid

    /**
     * Persists a full channel-sync pass in a single transaction: upserts each [configured] slot (matched by
     * `(radioID, index)`, preserving local-only fields), deletes rows at [unconfiguredIndices], and — when
     * [pruneBeyond] is non-null — deletes rows whose index is `>= pruneBeyond`. A slot in neither list is
     * left untouched, so a slot that could not be read is never deleted. Returns all rows after the write,
     * sorted by index.
     */
    suspend fun batchSaveChannels(
        radioID: Uuid,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        pruneBeyond: Int?,
    ): List<ChannelDto>

    /** Deletes the channel row with local id [id] and that slot's messages; a no-op if absent. */
    suspend fun deleteChannel(id: Uuid)

    /** Deletes every message (and cascaded reaction/repeat/pending-send) for a channel slot. */
    suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int)
}
