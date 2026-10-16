package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/**
 * Reconciles backup channels against the local channel set by stable cryptographic identity
 * `(radioID, secret)`, treating the slot `index` as mere placement. The pure port of the Swift
 * `PersistenceStore.batchInsertChannels` decision core (the volatile-slot trap): a channel joined on one
 * device must reconcile to the same local row on another regardless of which slot each device stored it
 * in, so the slot index never keys identity in an import path.
 *
 * A backup channel whose secret matches a local channel merges into it (recording a message-slot remap
 * when the slots differ); an empty-secret channel (an unconfigured slot, which carries no stable
 * identity) reconciles by slot instead; a channel with no secret match is placed at its
 * own slot when free, else relocated to the lowest free slot in `[1, maxChannels)` (slot 0 stays reserved
 * for the public channel), else dropped when no slot is free. This produces the placement plan only;
 * metadata merge (`mergeBackupMetadata`) and the message/reaction slot rewrite are applied by the backup
 * import wiring from [ChannelImportPlan.channelIndexRemap] and [ChannelImportPlan.droppedChannelIndices].
 */
object ChannelImportReconciler {
    /** Slot 0 is reserved for the public channel, so a relocating channel never lands there. */
    private const val FIRST_RELOCATABLE_SLOT: Int = ChannelSecret.PUBLIC_CHANNEL_SLOT + 1

    /**
     * Reconciles [backup] channels against the [local] snapshot. [maxChannelsByRadioID] bounds the
     * free-slot search per radio; when a radio has no entry the search is bounded by its highest occupied
     * slot plus one, so it terminates without inventing a capacity. [newLocalId] mints the surrogate id for
     * an inserted channel (mirroring the Swift fresh-id mint), so a second backup channel sharing a secret
     * merges into the first insert rather than inserting twice.
     */
    fun reconcile(
        local: List<ChannelDto>,
        backup: List<ChannelDto>,
        maxChannelsByRadioID: Map<Uuid, Int> = emptyMap(),
        newLocalId: () -> Uuid = Uuid::random,
    ): ChannelImportPlan {
        val state = ReconcileState()
        for (channel in local) state.indexLocal(channel)

        // Sort by (radioID, index) so free-slot assignment is deterministic regardless of envelope order.
        val ordered = backup.sortedWith(compareBy({ it.radioID.value }, { it.index }))
        for (dto in ordered) {
            val stable = ChannelSecret.hasStableSecret(dto.secret)
            val secretMatch = if (stable) state.bySecret[dto.radioID]?.get(dto.secret.toList()) else null
            val slotMatch = if (!stable) state.byIndex[dto.radioID]?.get(dto.index) else null
            when {
                secretMatch != null -> state.mergeBySecret(dto, secretMatch)
                slotMatch != null -> state.mergeBySlot(dto, slotMatch)
                else -> state.placeOrDrop(dto, stable, maxChannelsByRadioID[dto.radioID], newLocalId)
            }
        }

        return ChannelImportPlan(
            decisions = state.decisions,
            channelIndexRemap = state.remap,
            droppedChannelIndices = state.dropped,
            insertedLocalIndices = state.insertedIndices,
        )
    }

    /** The mutable reconcile pass: the local-channel indexes plus the plan being accumulated. */
    private class ReconcileState {
        val bySecret = mutableMapOf<Uuid, MutableMap<List<Byte>, LocalChannel>>()
        val byIndex = mutableMapOf<Uuid, MutableMap<Int, LocalChannel>>()
        val occupied = mutableMapOf<Uuid, MutableSet<Int>>()
        val decisions = mutableListOf<ChannelImportDecision>()
        val remap = mutableMapOf<Uuid, MutableMap<Int, Int>>()
        val dropped = mutableMapOf<Uuid, MutableSet<Int>>()
        val insertedIndices = mutableMapOf<Uuid, MutableSet<Int>>()

        fun indexLocal(channel: ChannelDto) {
            val entry = LocalChannel(channel.id, channel.index)
            occupied.getOrPut(channel.radioID) { mutableSetOf() }.add(channel.index)
            byIndex.getOrPut(channel.radioID) { mutableMapOf() }[channel.index] = entry
            if (ChannelSecret.hasStableSecret(channel.secret)) {
                bySecret.getOrPut(channel.radioID) { mutableMapOf() }[channel.secret.toList()] = entry
            }
        }

        fun mergeBySecret(dto: ChannelDto, existing: LocalChannel) {
            decisions += ChannelImportDecision.MergeBySecret(dto, existing.id, existing.index)
            if (existing.index != dto.index) remapEntry(dto.radioID)[dto.index] = existing.index
        }

        fun mergeBySlot(dto: ChannelDto, existing: LocalChannel) {
            decisions += ChannelImportDecision.MergeBySlot(dto, existing.id)
        }

        fun placeOrDrop(dto: ChannelDto, stable: Boolean, maxChannels: Int?, newLocalId: () -> Uuid) {
            val radioID = dto.radioID
            val placement = resolvePlacementIndex(dto.index, occupied[radioID].orEmpty(), maxChannels)
            if (placement == null) {
                decisions += ChannelImportDecision.Drop(dto)
                dropped.getOrPut(radioID) { mutableSetOf() }.add(dto.index)
                return
            }
            val localId = newLocalId()
            decisions += ChannelImportDecision.Insert(dto, placement, localId)
            val entry = LocalChannel(localId, placement)
            occupied.getOrPut(radioID) { mutableSetOf() }.add(placement)
            byIndex.getOrPut(radioID) { mutableMapOf() }[placement] = entry
            if (stable) bySecret.getOrPut(radioID) { mutableMapOf() }[dto.secret.toList()] = entry
            insertedIndices.getOrPut(radioID) { mutableSetOf() }.add(placement)
            if (placement != dto.index) remapEntry(radioID)[dto.index] = placement
        }

        private fun remapEntry(radioID: Uuid): MutableMap<Int, Int> = remap.getOrPut(radioID) { mutableMapOf() }
    }

    /**
     * Lowest free slot for a relocating channel: the backup's own slot when free, else the lowest
     * unoccupied index in `[FIRST_RELOCATABLE_SLOT, upperBound)`, else null so the caller drops the channel
     * rather than mis-associate it. [maxChannels] sets `upperBound`; without it the bound is the highest
     * occupied slot plus one.
     */
    private fun resolvePlacementIndex(backupIndex: Int, occupied: Set<Int>, maxChannels: Int?): Int? {
        if (backupIndex !in occupied) return backupIndex
        val upperBound = maxChannels ?: ((occupied.maxOrNull() ?: 0) + 1)
        if (upperBound <= FIRST_RELOCATABLE_SLOT) return null
        return (FIRST_RELOCATABLE_SLOT until upperBound).firstOrNull { it !in occupied }
    }

    private data class LocalChannel(val id: Uuid, val index: Int)
}

/**
 * A single reconcile decision, mirroring the outcomes of `batchInsertChannels`. [backup] is the source
 * channel; the concrete case says how it reconciled against the local set.
 */
sealed interface ChannelImportDecision {
    val backup: ChannelDto

    /** Merged into the local channel [localId] matched by `(radioID, secret)`, currently at [localIndex]. */
    data class MergeBySecret(override val backup: ChannelDto, val localId: Uuid, val localIndex: Int) :
        ChannelImportDecision

    /** Empty-secret channel merged into the local channel [localId] occupying the same slot. */
    data class MergeBySlot(override val backup: ChannelDto, val localId: Uuid) : ChannelImportDecision

    /** Inserted as a new local channel [localId] at [placementIndex] (== `backup.index` unless relocated). */
    data class Insert(override val backup: ChannelDto, val placementIndex: Int, val localId: Uuid) :
        ChannelImportDecision

    /** Dropped: no free local slot within the radio's capacity, so its messages must be dropped too. */
    data class Drop(override val backup: ChannelDto) : ChannelImportDecision
}

/**
 * The outcome of [ChannelImportReconciler.reconcile]. [channelIndexRemap] maps `radioID -> (backupIndex ->
 * localIndex)` for every channel placed at a slot other than its backup slot; the wiring rewrites channel
 * messages/reactions with it. [droppedChannelIndices] are the `(radioID, backupIndex)` slots with no free
 * placement, whose messages the wiring drops. [insertedLocalIndices] are the local slots a foreign backup
 * channel newly occupied — the only slots whose local identity changed, so the caller clears their drafts.
 */
data class ChannelImportPlan(
    val decisions: List<ChannelImportDecision>,
    val channelIndexRemap: Map<Uuid, Map<Int, Int>>,
    val droppedChannelIndices: Map<Uuid, Set<Int>>,
    val insertedLocalIndices: Map<Uuid, Set<Int>>,
) {
    val insertedCount: Int get() = decisions.count { it is ChannelImportDecision.Insert }

    /** Reconciles that adopted an existing local row (secret or slot merge) — the Swift `skipped` count. */
    val mergedCount: Int
        get() = decisions.count { it is ChannelImportDecision.MergeBySecret || it is ChannelImportDecision.MergeBySlot }

    val droppedCount: Int get() = decisions.count { it is ChannelImportDecision.Drop }
}
