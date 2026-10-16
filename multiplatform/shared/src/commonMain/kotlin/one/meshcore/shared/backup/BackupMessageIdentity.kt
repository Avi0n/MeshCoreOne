package one.meshcore.shared.backup

import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid

/**
 * Reconciles backup messages against the rows already in the store. A message restored onto a store
 * that heard the same traffic live is the same message, so matching on `id` alone duplicates the whole
 * history; the match runs on [BackupMerge.messageBackupKey] instead — identity for outgoing rows,
 * content for incoming ones. Every family keyed by `messageID` (repeats, reactions) then has to follow
 * the winning local row, the same way [BackupContactIdentity] carries DM history onto a merged contact.
 * Mirrors Swift `batchInsertMessages` / `applyMessageIDMapping`.
 */
object BackupMessageIdentity {
    data class SkippedIncomingParent(val localMessageID: Uuid, val dto: MessageDto)

    /**
     * [toInsert] carries the messages that have no local counterpart, with `replyToID` already rewritten
     * onto winning local ids; [skipped] counts the ones a local row already covers; [messageIDByBackupID]
     * maps each skipped backup id to the local row that won it.
     */
    data class Plan(
        val toInsert: List<MessageDto>,
        val skipped: Int,
        val messageIDByBackupID: Map<Uuid, Uuid>,
        val skippedIncomingParents: List<SkippedIncomingParent>,
    )

    /**
     * Resolves [messages] against [local] in two passes: the first builds the backup→local id remap for
     * every duplicate (same backup key, or a [MessageDto.id] already claimed locally or earlier in this
     * envelope), the second rewrites `replyToID` through it so a reply chain whose parent already exists
     * locally points at the local parent instead of a backup id that never lands.
     */
    fun plan(messages: List<MessageDto>, local: List<MessageDto>): Plan {
        val idsByKey = mutableMapOf<String, MutableList<Uuid>>()
        for (dto in local) {
            idsByKey.getOrPut(BackupMerge.messageBackupKey(dto)) { mutableListOf() }.add(dto.id)
        }
        // Message.id is unique; seed from local so a colliding backup row cannot overwrite it.
        val knownIDs = local.mapTo(mutableSetOf()) { it.id }

        val messageIDByBackupID = mutableMapOf<Uuid, Uuid>()
        val skippedIncomingParents = mutableListOf<SkippedIncomingParent>()
        val candidates = mutableListOf<MessageDto>()
        var skipped = 0
        for (dto in messages) {
            val key = BackupMerge.messageBackupKey(dto)
            val existing = idsByKey[key]
            when {
                existing != null -> {
                    // Deterministic tiebreak when several local rows share one key.
                    val winning = existing.minByOrNull { it.value }
                    if (winning != null && winning != dto.id) messageIDByBackupID[dto.id] = winning
                    if (winning != null && dto.direction == MessageDirection.INCOMING) {
                        skippedIncomingParents.add(SkippedIncomingParent(winning, dto))
                    }
                    skipped++
                }

                // First id in the envelope wins; later rows with the same UUID are skipped, not remapped.
                !knownIDs.add(dto.id) -> skipped++

                else -> {
                    idsByKey[key] = mutableListOf(dto.id)
                    candidates.add(dto)
                }
            }
        }

        val toInsert = candidates.map { dto ->
            val winning = dto.replyToID?.let { messageIDByBackupID[it] }
            if (winning == null) dto else dto.copy(replyToID = winning)
        }
        return Plan(
            toInsert = toInsert,
            skipped = skipped,
            messageIDByBackupID = messageIDByBackupID,
            skippedIncomingParents = skippedIncomingParents,
        )
    }

    /** Rewrites `messageID` on reactions so they attach to the winning local parent. */
    fun applyToReactions(mapping: Map<Uuid, Uuid>, reactions: List<ReactionDto>): List<ReactionDto> {
        if (mapping.isEmpty()) return reactions
        return reactions.map { dto -> mapping[dto.messageID]?.let { dto.copy(messageID = it) } ?: dto }
    }

    /** Rewrites `messageID` on message repeats so they attach to the winning local parent. */
    fun applyToRepeats(mapping: Map<Uuid, Uuid>, repeats: List<MessageRepeatDto>): List<MessageRepeatDto> {
        if (mapping.isEmpty()) return repeats
        return repeats.map { dto -> mapping[dto.messageID]?.let { dto.copy(messageID = it) } ?: dto }
    }
}
