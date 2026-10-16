package one.meshcore.shared.chat.reactions

import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.model.Uuid

internal suspend fun ReactionHandler.handledOpenDirect(
    text: String,
    contact: ResolvedContact,
    radioID: Uuid,
): Boolean {
    val mco = MeshCoreOpenReactionParser.parse(text)
    if (mco != null) return handleMcoDirect(mco, text, contact, radioID)
    return handledOpenV1Direct(text, contact, radioID)
}

private suspend fun ReactionHandler.handledOpenV1Direct(
    text: String,
    contact: ResolvedContact,
    radioID: Uuid,
): Boolean {
    val parsed = MeshCoreOpenReactionParser.parseV1(text) ?: return false
    return handleMcoV1Direct(parsed, text, contact, radioID)
}

internal suspend fun ReactionHandler.persistParsedDirect(
    parsed: ParsedDmReaction,
    text: String,
    contact: ResolvedContact,
    radioID: Uuid,
): Boolean {
    val messageId = directTargetId(parsed, contact, radioID)
    if (messageId != null) {
        persistDm(
            DirectReactionRow(
                messageID = messageId,
                emoji = parsed.emoji,
                senderName = contact.displayName,
                messageHash = parsed.messageHash,
                rawText = text,
                contactID = contact.id,
                radioID = radioID,
            ),
        )
        return true
    }
    reactionService.queuePendingDmReaction(parsed, contact.id, contact.displayName, text, radioID)
    return true
}

private suspend fun ReactionHandler.directTargetId(
    parsed: ParsedDmReaction,
    contact: ResolvedContact,
    radioID: Uuid,
): Uuid? {
    val cached = reactionService.findDmTargetMessage(parsed.messageHash, contact.id)
    if (cached != null) return cached
    return store.findDirectMessageForReaction(
        radioID = radioID,
        contactID = contact.id,
        messageHash = parsed.messageHash,
        window = window(nowSeconds()),
        limit = ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )?.id
}

private suspend fun ReactionHandler.handleMcoDirect(
    mco: ParsedMcoReaction,
    rawText: String,
    contact: ResolvedContact,
    radioID: Uuid,
): Boolean {
    val candidates = store.fetchDirectMessageCandidates(
        radioID,
        contact.id,
        window(nowSeconds()),
        ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )
    val match = candidates.firstOrNull { candidate ->
        !ReactionParser.isReactionText(candidate.text, isDM = true) &&
            MeshCoreOpenReactionParser.computeReactionHash(
                candidate.reactionTimestamp,
                null,
                candidate.text,
            ) == mco.dartHash
    }
    if (match != null) {
        persistDm(
            DirectReactionRow(
                messageID = match.id,
                emoji = mco.emoji,
                senderName = contact.displayName,
                messageHash = mco.dartHash,
                rawText = rawText,
                contactID = contact.id,
                radioID = radioID,
            ),
        )
    }
    return true
}

private suspend fun ReactionHandler.handleMcoV1Direct(
    v1: ParsedMcoReactionV1,
    rawText: String,
    contact: ResolvedContact,
    radioID: Uuid,
): Boolean {
    val candidates = store.fetchDirectMessageCandidates(
        radioID,
        contact.id,
        window(v1.timestampSeconds),
        ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )
    val match = candidates.firstOrNull { candidate ->
        !ReactionParser.isReactionText(candidate.text, isDM = true) &&
            MeshCoreOpenReactionParser.dartStringHash(candidate.text) == v1.textHash
    }
    if (match != null) {
        persistDm(
            DirectReactionRow(
                messageID = match.id,
                emoji = v1.emoji,
                senderName = contact.displayName,
                messageHash = v1.messageIdHash,
                rawText = rawText,
                contactID = contact.id,
                radioID = radioID,
            ),
        )
    }
    return true
}
