package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid

/**
 * Rewrites `contactID` on the families keyed by it once contacts are reconciled: a backup contact
 * that merged into an existing local row keeps the local row's id, so its DM history and reactions
 * must follow it or they attach to a contact row that does not exist locally. Mirrors Swift
 * `PersistenceStore.buildContactIDMapping` / `applyContactIDMapping`; the radioID analog is
 * [BackupRadioIdentity].
 */
object BackupContactIdentity {
    data class Result(val messages: List<MessageDto>, val reactions: List<ReactionDto>)

    /**
     * Backup-contact-id → local-contact-id entries for contacts that merged into an existing local
     * row. Identity mappings are omitted so a same-install restore rewrites nothing.
     */
    fun buildContactIDMapping(contacts: List<ContactDto>, contactIDsByKey: Map<String, Uuid>): Map<Uuid, Uuid> =
        mergedLocalIdMapping(
            rows = contacts,
            key = { BackupMergeKeys.contactKey(it.radioID, it.publicKey) },
            foreignId = { it.id },
            localIdsByKey = contactIDsByKey,
        )

    /**
     * Rewrites `contactID` on messages (with their DM dedup key, so a second import of the same
     * backup still deduplicates) and on reactions.
     */
    fun apply(mapping: Map<Uuid, Uuid>, messages: List<MessageDto>, reactions: List<ReactionDto>): Result {
        if (mapping.isEmpty()) return Result(messages, reactions)
        return Result(
            messages = messages.map { remapMessage(it, mapping) },
            reactions = reactions.map { dto ->
                val localID = dto.contactID?.let { mapping[it] } ?: return@map dto
                dto.copy(contactID = localID)
            },
        )
    }

    private fun remapMessage(dto: MessageDto, mapping: Map<Uuid, Uuid>): MessageDto {
        val backupID = dto.contactID ?: return dto
        val localID = mapping[backupID] ?: return dto
        val dedup = dto.deduplicationKey?.let { BackupMerge.rewriteDMDeduplicationKey(it, backupID, localID) }
        return dto.copy(contactID = localID, deduplicationKey = dedup)
    }
}

/**
 * Backup-id → local-id entries for rows that merged into an existing local row.
 * An identity mapping is omitted so a same-install restore rewrites nothing.
 */
internal fun <T> mergedLocalIdMapping(
    rows: List<T>,
    key: (T) -> String,
    foreignId: (T) -> Uuid,
    localIdsByKey: Map<String, Uuid>,
): Map<Uuid, Uuid> {
    val mapping = mutableMapOf<Uuid, Uuid>()
    for (row in rows) {
        val localID = localIdsByKey[key(row)] ?: continue
        val id = foreignId(row)
        if (localID != id) mapping[id] = localID
    }
    return mapping
}
