package one.meshcore.shared.backup

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.channel.ChannelImportDecision
import one.meshcore.shared.channel.ChannelImportPlan
import one.meshcore.shared.channel.ChannelImportReconciler
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.settings.PersistentLogger
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime

/**
 * Import path for a decoded [AppBackupEnvelope]: matches backup devices to local devices by
 * publicKey ([BackupRadioIdentity]), remaps every family's `radioID` onto the resolved local
 * partition before inserting anything, and merges metadata into existing rows via [BackupMerge].
 * Channels reconcile by stable secret (volatile slot) through [ChannelImportReconciler]; messages
 * and reactions for dropped channels are omitted rather than mis-associated. Message repeats, room
 * messages, saved trace paths, blocked channel senders, and node status snapshots import through
 * [BackupExtraFamiliesImporter].
 */
@OptIn(ExperimentalTime::class)
object RoomBackupImporter {
    suspend fun importEnvelope(
        database: MeshDatabase,
        envelope: AppBackupEnvelope,
        radioConnected: () -> Boolean = { false },
    ): BackupImportResult = importEnvelope(database, envelope, beforeCommit = null, radioConnected = radioConnected)

    /**
     * Validates the envelope, then runs every write of the import inside one exclusive writer
     * transaction so a failure rolls back only this pass. That is the Room analog of iOS
     * `createStandalonePersistenceStore`: rollback cannot discard live sync writes sitting in
     * another context. [radioConnected] is re-read after the writer is held, so a reconnect that
     * landed while the preview was open abandons the merge before any row is written.
     */
    internal suspend fun importEnvelope(
        database: MeshDatabase,
        envelope: AppBackupEnvelope,
        beforeCommit: (suspend () -> Unit)?,
        radioConnected: () -> Boolean = { false },
    ): BackupImportResult {
        val owner = currentCoroutineContext()
        envelope.validateForImport()
        if (radioConnected()) throw AppBackupError.RadioConnected()
        return database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                owner.ensureActive()
                if (radioConnected()) throw AppBackupError.RadioConnected()
                val result = runImport(database, envelope, owner)
                beforeCommit?.invoke()
                owner.ensureActive()
                result
            }
        }
    }

    private suspend fun runImport(
        database: MeshDatabase,
        envelope: AppBackupEnvelope,
        owner: CoroutineContext,
    ): BackupImportResult {
        val logger = PersistentLogger(CoroutineScope(currentCoroutineContext()), category = "AppBackupService")
        val cleanedDevices = envelope.devices.map { it.cleanedForImport() }
        val radioMapping = resolveRadioIdentity(database, cleanedDevices)
        if (radioMapping.duplicateDeviceCount > 0) {
            logger.warning(backupDuplicateDeviceLine(radioMapping.duplicateDeviceCount))
        }
        val remapped = remapFamilies(envelope, radioMapping.mapping)

        val deviceCounts = BackupCoreFamiliesImporter.importDevices(database, radioMapping)
        owner.ensureActive()
        val contactOutcome = BackupCoreFamiliesImporter.importContacts(database, remapped.contacts)

        val contactMapped = applyContactIdentity(remapped, contactOutcome)
        val channelOutcome = BackupCoreFamiliesImporter.importChannels(
            database,
            remapped.channels,
            cleanedDevices,
            radioMapping.mapping,
        )
        logDroppedChannels(logger, channelOutcome.plan)
        owner.ensureActive()
        val sessionOutcome = BackupCoreFamiliesImporter.importSessions(database, remapped.sessions)

        val mapped =
            BackupChannelIndexMapping.apply(
                messages = contactMapped.messages,
                reactions = contactMapped.reactions,
                channelIndexRemap = channelOutcome.plan.channelIndexRemap,
                droppedChannelIndices = channelOutcome.plan.droppedChannelIndices,
            )
        val messageOutcome = BackupCoreFamiliesImporter.importMessages(
            database,
            mapped.messages,
            BackupCoreFamiliesImporter.allRadioIDs(radioMapping.mapping, remapped),
        )

        // Messages are final here, so anything keyed by messageID follows a deduplicated message onto
        // the local row that won it, rather than referencing a backup id that never landed.
        val reactionOutcome =
            BackupCoreFamiliesImporter.importReactions(
                database,
                BackupMessageIdentity.applyToReactions(messageOutcome.mapping, mapped.reactions),
                resolvedMessageIDs(mapped.messages, messageOutcome.mapping),
            )

        val result = finishImport(
            ImportFinish(
                database = database,
                envelope = envelope,
                remapped = remapped,
                mapped = mapped,
                sessionOutcome = sessionOutcome,
                messageOutcome = messageOutcome,
                reactionOutcome = reactionOutcome,
                radioMapping = radioMapping,
                contactOutcome = contactOutcome,
                channelOutcome = channelOutcome,
                deviceCounts = deviceCounts,
            ),
        )
        logger.info(
            backupImportCompleteLine(result.totalInserted, result.totalMerged, result.totalSkipped),
        )
        return result
    }

    private fun logDroppedChannels(logger: PersistentLogger, plan: ChannelImportPlan) {
        for (drop in plan.decisions.filterIsInstance<ChannelImportDecision.Drop>()) {
            logger.warning(backupChannelSlotDropLine(drop.backup.index, drop.backup.radioID))
        }
    }

    private suspend fun finishImport(input: ImportFinish): BackupImportResult {
        val extra =
            importExtraFamilies(
                input.database,
                input.envelope,
                input.remapped,
                input.mapped,
                BackupIdentityMaps(
                    input.sessionOutcome.sessionIDsByKey,
                    input.messageOutcome.mapping,
                    input.messageOutcome.skippedIncomingParents,
                ),
            )
        reconcileDerivedState(
            input.database,
            input.mapped,
            extra,
            input.reactionOutcome.affectedMessageIDs,
        )
        return BackupImportResult(
            devicesInserted = input.deviceCounts.first,
            devicesMerged = input.deviceCounts.second,
            contactsInserted = input.contactOutcome.inserted,
            contactsMerged = input.contactOutcome.merged,
            contactsSkipped = input.contactOutcome.skipped,
            channelsInserted = input.channelOutcome.inserted,
            channelsMerged = input.channelOutcome.merged,
            channelsSkipped = input.channelOutcome.skipped,
            channelsDropped = input.channelOutcome.dropped,
            sessionsInserted = input.sessionOutcome.inserted,
            sessionsMerged = input.sessionOutcome.merged,
            sessionsSkipped = input.sessionOutcome.skipped,
            messagesInserted = input.messageOutcome.inserted,
            messagesSkipped = input.messageOutcome.skipped,
            messagesDropped = input.mapped.messagesDropped,
            reactionsInserted = input.reactionOutcome.inserted,
            reactionsSkipped = input.reactionOutcome.skipped,
            reactionsDropped = input.mapped.reactionsDropped,
            messageRepeatsInserted = extra.messageRepeatsInserted,
            messageRepeatsSkipped = extra.messageRepeatsSkipped,
            roomMessagesInserted = extra.roomMessagesInserted,
            roomMessagesSkipped = extra.roomMessagesSkipped,
            savedTracePathsInserted = extra.tracePathsInserted,
            savedTracePathsSkipped = extra.tracePathsSkipped,
            savedTracePathsMerged = extra.tracePathsMerged,
            blockedChannelSendersInserted = extra.blockedSendersInserted,
            blockedChannelSendersSkipped = extra.blockedSendersSkipped,
            nodeStatusSnapshotsInserted = extra.snapshotsInserted,
            nodeStatusSnapshotsSkipped = extra.snapshotsSkipped,
            discoveredNodesInserted = extra.discoveredNodesInserted,
            discoveredNodesSkipped = extra.discoveredNodesSkipped,
            devicesSkipped = input.radioMapping.duplicateDeviceCount,
            discoveredNodesDropped = extra.discoveredNodesDropped,
            channelSlotsAffected = BackupCoreFamiliesImporter.channelSlotsAffected(input.channelOutcome.plan),
        )
    }

    /** Matches the backup's devices to the local partitions by publicKey, the key every family remaps onto. */
    private suspend fun resolveRadioIdentity(
        database: MeshDatabase,
        devices: List<DeviceDto>,
    ): BackupRadioIdentity.RadioIDMapping {
        val localRadioIDsByPublicKey =
            database.deviceDao().listAll().associate { it.publicKey.toBase64Key() to it.radioID }
        return BackupRadioIdentity.buildRadioIDMapping(devices, localRadioIDsByPublicKey)
    }

    /**
     * The two recomputes that read rows the family importers just wrote: the per-message repeat and
     * reaction caches a bubble renders from, then the conversation `lastMessageDate` the chat list
     * sorts and filters on. Both run inside the import transaction, after every insert.
     */
    private suspend fun reconcileDerivedState(
        database: MeshDatabase,
        mapped: BackupChannelIndexMapping.Result,
        extra: ExtraFamiliesOutcome,
        reactionAffectedMessageIDs: Set<Uuid>,
    ) {
        // Runs after both child families so a message that gained a reaction and a repeat is rewritten once.
        recomputeMessageCaches(database, reactionAffectedMessageIDs + extra.messageRepeatAffectedMessageIDs)
        BackupLastMessageDateReconciler.reconcile(
            database = database,
            messages = mapped.messages,
            roomMessages = extra.roomMessages,
            affectedSessionIDs = extra.roomMessageAffectedSessionIDs,
        )
    }

    /**
     * Carries DM history onto the local contact row a backup contact merged into. Runs before anything
     * keyed by `contactID` is inserted, so no restored message attaches to a contact that never landed.
     */
    private fun applyContactIdentity(
        remapped: RemappedFamilies,
        contactOutcome: ContactImportOutcome,
    ): BackupContactIdentity.Result = BackupContactIdentity.apply(
        mapping = BackupContactIdentity.buildContactIDMapping(remapped.contacts, contactOutcome.contactIDsByKey),
        messages = remapped.messages,
        reactions = remapped.reactions,
    )

    private data class ImportFinish(
        val database: MeshDatabase,
        val envelope: AppBackupEnvelope,
        val remapped: RemappedFamilies,
        val mapped: BackupChannelIndexMapping.Result,
        val sessionOutcome: SessionImportOutcome,
        val messageOutcome: MessageImportOutcome,
        val reactionOutcome: ReactionImportOutcome,
        val radioMapping: BackupRadioIdentity.RadioIDMapping,
        val contactOutcome: ContactImportOutcome,
        val channelOutcome: ChannelImportOutcome,
        val deviceCounts: Pair<Int, Int>,
    )
}
