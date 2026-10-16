package one.meshcore.shared.backup

import one.meshcore.shared.channel.ChannelImportDecision
import one.meshcore.shared.channel.ChannelImportPlan
import one.meshcore.shared.channel.ChannelImportReconciler
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Writes one backed-up model family inside the importer's single transaction. */
@OptIn(ExperimentalTime::class)
internal object BackupCoreFamiliesImporter {
    /**
     * Inserts devices [BackupRadioIdentity] couldn't match to a local publicKey, minting a fresh
     * local `id` (the backup's `id` was a volatile live-connection handle on the source phone) while
     * keeping the device's own `radioID` intact so its already-remapped child rows stay attached.
     * A publicKey match leaves the local row untouched and is not a merge: iOS records those as
     * skipped, so a re-import of the already-paired radio does not look restored.
     */
    suspend fun importDevices(
        database: MeshDatabase,
        radioMapping: BackupRadioIdentity.RadioIDMapping,
    ): Pair<Int, Int> {
        var inserted = 0
        for (foreign in radioMapping.unmatchedDevices) {
            database.deviceDao().upsert(foreign.copy(id = newLocalId()).toEntity())
            inserted++
        }
        return inserted to 0
    }

    /**
     * Reconciles backup contacts against local rows by `(radioID, publicKey)`, and reports the local
     * contact id each key resolved to so [BackupContactIdentity] can carry the dependent families
     * onto it. A key seen twice within one envelope merges into the first occurrence rather than
     * inserting a second row for the same identity, mirroring iOS `batchInsertContacts`.
     */
    suspend fun importContacts(database: MeshDatabase, contacts: List<ContactDto>): ContactImportOutcome {
        var inserted = 0
        var merged = 0
        var skipped = 0
        val byKey =
            database.contactDao().listAll()
                .associateBy({ BackupMergeKeys.contactKey(it.radioID, it.publicKey) }, { it.toDto() })
                .toMutableMap()
        val contactIDsByKey = mutableMapOf<String, Uuid>()
        for (foreign in contacts) {
            val key = BackupMergeKeys.contactKey(foreign.radioID, foreign.publicKey)
            val existing = byKey[key]
            if (existing == null) {
                val now = Clock.System.now().epochSeconds
                val insertedContact = foreign.copy(
                    lastHeardTimestamp = BackupMerge.lastHeardForInsert(foreign.lastHeardTimestamp, now),
                )
                database.contactDao().upsert(insertedContact.toEntity())
                byKey[key] = insertedContact
                contactIDsByKey[key] = insertedContact.id
                inserted++
            } else {
                skipped++
                val result = BackupMerge.mergeContactMetadata(existing, foreign)
                if (result.changed) {
                    database.contactDao().upsert(result.value.toEntity())
                    byKey[key] = result.value
                    merged++
                }
                contactIDsByKey[key] = existing.id
            }
        }
        return ContactImportOutcome(inserted, merged, skipped, contactIDsByKey)
    }

    /**
     * Reconciles backup channels by `(radioID, secret)` placement, merges metadata into matched
     * locals, inserts relocated/new rows with fresh surrogate ids, and counts drops when capacity
     * is exhausted.
     */
    suspend fun importChannels(
        database: MeshDatabase,
        channels: List<ChannelDto>,
        devices: List<DeviceDto>,
        radioMapping: Map<Uuid, Uuid>,
    ): ChannelImportOutcome {
        val local = database.channelDao().listAll().map { it.toDto() }
        val maxChannelsByRadioID = maxChannelsByRadioID(devices, radioMapping)
        val plan =
            ChannelImportReconciler.reconcile(
                local = local,
                backup = channels,
                maxChannelsByRadioID = maxChannelsByRadioID,
                newLocalId = ::newLocalId,
            )

        var inserted = 0
        var merged = 0
        for (decision in plan.decisions) {
            when (decision) {
                is ChannelImportDecision.Insert -> {
                    val placed =
                        decision.backup.copy(
                            id = decision.localId,
                            index = decision.placementIndex,
                        )
                    database.channelDao().upsert(placed.toEntity())
                    inserted++
                }
                is ChannelImportDecision.MergeBySecret -> {
                    if (applyChannelMerge(database, decision.localId, decision.backup)) merged++
                }
                is ChannelImportDecision.MergeBySlot -> {
                    if (applyChannelMerge(database, decision.localId, decision.backup)) merged++
                }
                is ChannelImportDecision.Drop -> Unit
            }
        }
        return ChannelImportOutcome(
            inserted = inserted,
            merged = merged,
            skipped = plan.mergedCount,
            dropped = plan.droppedCount,
            plan = plan,
        )
    }

    suspend fun applyChannelMerge(database: MeshDatabase, localId: Uuid, foreign: ChannelDto): Boolean {
        val existing = database.channelDao().getById(localId) ?: return false
        val result = BackupMerge.mergeChannelMetadata(existing.toDto(), foreign)
        if (!result.changed) return false
        database.channelDao().upsert(result.value.toEntity())
        return true
    }

    /**
     * Resolves each envelope device's channel capacity to the local radioID it maps to, mirroring iOS
     * `maxChannelsByLocalRadioID`. The envelope is the only source: a local device row can hold a
     * stale, smaller capacity, and letting it win drops a relocating channel, its messages and its
     * reactions where iOS places them.
     */
    fun maxChannelsByRadioID(devices: List<DeviceDto>, radioMapping: Map<Uuid, Uuid>): Map<Uuid, Int> {
        val map = mutableMapOf<Uuid, Int>()
        for (device in devices) {
            val localRadioID = radioMapping[device.radioID] ?: device.radioID
            // First occurrence wins, consistent with duplicate-device handling upstream.
            if (map[localRadioID] == null) map[localRadioID] = device.maxChannels
        }
        return map
    }

    fun channelSlotsAffected(plan: ChannelImportPlan): Map<Uuid, Set<Int>> {
        val result = mutableMapOf<Uuid, MutableSet<Int>>()
        for ((radioID, indices) in plan.insertedLocalIndices) {
            result.getOrPut(radioID) { mutableSetOf() }.addAll(indices)
        }
        for ((radioID, indices) in plan.droppedChannelIndices) {
            result.getOrPut(radioID) { mutableSetOf() }.addAll(indices)
        }
        return result
    }

    suspend fun importSessions(database: MeshDatabase, sessions: List<RemoteNodeSessionDto>): SessionImportOutcome {
        var inserted = 0
        var merged = 0
        var skipped = 0
        val byKey =
            database.remoteNodeSessionDao().getAll()
                .associateBy { BackupMergeKeys.remoteNodeSessionKey(it.radioID, it.publicKey) }
                .toMutableMap()
        val sessionIDsByKey = mutableMapOf<String, Uuid>()
        for (foreign in sessions) {
            val key = BackupMergeKeys.remoteNodeSessionKey(foreign.radioID, foreign.publicKey)
            val existing = byKey[key]
            if (existing == null) {
                // A restored session is never a live login; leaving the flag set would show a room or
                // repeater as logged in until the next process start.
                val insertedSession = foreign.copy(isConnected = false)
                database.remoteNodeSessionDao().upsert(insertedSession.toEntity())
                byKey[key] = insertedSession.toEntity()
                inserted++
                sessionIDsByKey[key] = insertedSession.id
            } else {
                skipped++
                val result = BackupMerge.mergeRemoteNodeSessionMetadata(existing.toDto(), foreign)
                if (result.changed) {
                    database.remoteNodeSessionDao().upsert(result.value.toEntity())
                    byKey[key] = result.value.toEntity()
                    merged++
                }
                sessionIDsByKey[key] = existing.id
            }
        }
        return SessionImportOutcome(inserted, merged, skipped, sessionIDsByKey)
    }

    /**
     * Every radio partition this import touches: the resolved local partitions plus the ones only the
     * backup's own rows name. Mirrors iOS `allRadioIDs`, and bounds the local-message scan that
     * [BackupMessageIdentity] reconciles against.
     */
    fun allRadioIDs(mapping: Map<Uuid, Uuid>, remapped: RemappedFamilies): Set<Uuid> = buildSet {
        addAll(mapping.values)
        remapped.contacts.mapTo(this) { it.radioID }
        remapped.channels.mapTo(this) { it.radioID }
        remapped.messages.mapTo(this) { it.radioID }
        remapped.reactions.mapTo(this) { it.radioID }
        remapped.sessions.mapTo(this) { it.radioID }
        remapped.tracePaths.mapTo(this) { it.radioID }
        remapped.blockedSenders.mapTo(this) { it.radioID }
        remapped.discoveredNodes.mapTo(this) { it.radioID }
    }

    /**
     * Insert-only. Dedupes on [BackupMerge.messageBackupKey] so a restore onto a store that heard the
     * same traffic live does not duplicate history (the two stores mint different ids for one packet).
     * Also skips a DTO whose [MessageDto.id] is already local or already accepted in this envelope, so
     * Room upsert cannot overwrite the first row.
     */
    suspend fun importMessages(
        database: MeshDatabase,
        messages: List<MessageDto>,
        radioIDs: Set<Uuid>,
    ): MessageImportOutcome {
        if (messages.isEmpty()) {
            return MessageImportOutcome(0, 0, emptyMap(), emptyList())
        }
        val local = radioIDs.flatMap { radioID -> database.messageDao().listByRadio(radioID).map { it.toDto() } }
        val plan = BackupMessageIdentity.plan(messages, local)
        for (dto in plan.toInsert) {
            // iOS `Message(dto:)` never copies cached preview blobs; a restored row re-fetches.
            database.messageDao().upsert(dto.redactedForBackup().toEntity())
        }
        return MessageImportOutcome(
            inserted = plan.toInsert.size,
            skipped = plan.skipped,
            mapping = plan.messageIDByBackupID,
            skippedIncomingParents = plan.skippedIncomingParents,
        )
    }

    /**
     * Insert-only, deduped by `(messageID, senderName, emoji)` like iOS `existingReactionKeys` — the
     * same reaction re-received live and restored from a backup carries two different row ids.
     * Reactions whose parent message didn't survive import (dropped with its channel, or simply
     * absent) are skipped rather than orphaned, mirroring iOS `batchInsertReactions`.
     */
    suspend fun importReactions(
        database: MeshDatabase,
        reactions: List<ReactionDto>,
        existingMessageIDs: Set<Uuid>,
    ): ReactionImportOutcome {
        if (reactions.isEmpty()) {
            return ReactionImportOutcome(inserted = 0, skipped = 0, affectedMessageIDs = emptySet())
        }
        val referencedParentIDs = reactions.map { it.messageID }.distinct().filter { it in existingMessageIDs }
        val existingKeys =
            referencedParentIDs
                .flatMap { database.reactionDao().listByMessage(it) }
                .mapTo(mutableSetOf()) { BackupMergeKeys.reactionKey(it.messageID, it.senderName, it.emoji) }
        var inserted = 0
        var skipped = 0
        val affectedMessageIDs = mutableSetOf<Uuid>()
        for (foreign in reactions) {
            val key = BackupMergeKeys.reactionKey(foreign.messageID, foreign.senderName, foreign.emoji)
            // foreign.messageID absent from existingMessageIDs short-circuits before the add() side effect.
            if (foreign.messageID !in existingMessageIDs || !existingKeys.add(key)) {
                skipped++
                continue
            }
            database.reactionDao().upsert(foreign.toEntity())
            affectedMessageIDs.add(foreign.messageID)
            inserted++
        }
        return ReactionImportOutcome(inserted, skipped, affectedMessageIDs)
    }
}

internal data class ChannelImportOutcome(
    val inserted: Int,
    val merged: Int,
    val skipped: Int,
    val dropped: Int,
    val plan: ChannelImportPlan,
)

internal data class ContactImportOutcome(
    val inserted: Int,
    val merged: Int,
    val skipped: Int,
    val contactIDsByKey: Map<String, Uuid>,
)

/** [mapping] is backup-message-id → winning local id for every message that deduplicated. */
internal data class MessageImportOutcome(
    val inserted: Int,
    val skipped: Int,
    val mapping: Map<Uuid, Uuid>,
    val skippedIncomingParents: List<BackupMessageIdentity.SkippedIncomingParent>,
)

/** Insert count plus the parent messages that gained a reaction, whose summary cache is now stale. */
internal data class ReactionImportOutcome(val inserted: Int, val skipped: Int, val affectedMessageIDs: Set<Uuid>)

internal data class SessionImportOutcome(
    val inserted: Int,
    val merged: Int,
    val skipped: Int,
    val sessionIDsByKey: Map<String, Uuid>,
)
