package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity

/** Persists incoming path arrivals that matched an existing backup message. */
internal object BackupIncomingPathImporter {
    data class IncomingPathOutcome(
        val promoted: Int,
        val skippedRepeats: Int,
        val affectedMessageIDs: Set<Uuid>,
        val repeatsToImport: List<MessageRepeatDto>,
    )

    /** Merge skipped incoming parents before importing children, within the caller's transaction. */
    suspend fun mergeIncomingPathArrivals(
        database: MeshDatabase,
        skippedParents: List<BackupMessageIdentity.SkippedIncomingParent>,
        repeats: List<MessageRepeatDto>,
        messageIDs: Set<Uuid>,
    ): IncomingPathOutcome {
        val skippedByID = skippedParents.groupBy { it.localMessageID }
        val repeatsByID = repeats.groupBy { it.messageID }
        val relevantIDs = (skippedByID.keys + repeatsByID.keys).filter { it in messageIDs }
        val kept = mutableListOf<MessageRepeatDto>()
        val affected = mutableSetOf<Uuid>()
        var promoted = 0
        var skipped = 0
        for (id in relevantIDs) {
            val parent = database.messageDao().getById(id) ?: continue
            val existing = database.messageRepeatDao().listByMessage(id).map { it.toDto() }
            val plan = BackupIncomingPathMerge.plan(
                parent = parent.toDto(),
                skippedParents = skippedByID[id].orEmpty().map { it.dto },
                existingRepeats = existing,
                incomingRepeats = repeatsByID[id].orEmpty(),
            )
            if (parent.pathNodes == null && plan.parent.pathNodes != null) {
                database.messageDao().upsert(
                    parent.copy(
                        pathNodes = plan.parent.pathNodes,
                        pathLength = plan.parent.pathLength,
                    ),
                )
                affected.add(id)
            }
            for (repeat in plan.promotedRepeats) {
                database.messageRepeatDao().upsert(repeat.toEntity())
                affected.add(id)
                promoted++
            }
            kept.addAll(plan.repeatsToImport)
            skipped += plan.skippedRepeats
        }
        kept.addAll(repeats.filter { it.messageID !in relevantIDs })
        return IncomingPathOutcome(promoted, skipped, affected, kept)
    }
}
