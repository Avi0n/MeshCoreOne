package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChannelReactionWire
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.chat.reactionTimestamp

internal suspend fun ReactionHandler.handledOpenChannel(wire: ChannelReactionWire): Boolean {
    val mco = MeshCoreOpenReactionParser.parse(wire.text)
    if (mco != null) {
        return handleMcoChannel(mco, wire)
    }
    return handledOpenV1Channel(wire)
}

private suspend fun ReactionHandler.handledOpenV1Channel(wire: ChannelReactionWire): Boolean {
    val parsed = MeshCoreOpenReactionParser.parseV1(wire.text) ?: return false
    return handleMcoV1Channel(parsed, wire)
}

internal suspend fun ReactionHandler.persistPocketMeshChannel(wire: ChannelReactionWire): Boolean {
    val parsed = ReactionParser.parse(wire.text) ?: return false
    return persistParsedChannel(wire, parsed)
}

private suspend fun ReactionHandler.persistParsedChannel(wire: ChannelReactionWire, parsed: ParsedReaction): Boolean {
    val senderName = wire.senderNodeName ?: unknownSenderLabel()
    val cached = reactionService.findTargetMessage(parsed, wire.channelIndex)
    if (cached != null) {
        persistChannel(
            ChannelReactionRow(
                messageID = cached,
                emoji = parsed.emoji,
                senderName = senderName,
                messageHash = parsed.messageHash,
                rawText = wire.text,
                channelIndex = wire.channelIndex,
                radioID = wire.radioID,
            ),
        )
        return true
    }
    return persistStoredChannel(wire, parsed, senderName)
}

private suspend fun ReactionHandler.persistStoredChannel(
    wire: ChannelReactionWire,
    parsed: ParsedReaction,
    senderName: String,
): Boolean {
    val target = store.findChannelMessageForReaction(
        lookup = ChannelReactionLookup(
            radioID = wire.radioID,
            channelIndex = wire.channelIndex,
            parsed = parsed,
            localNodeName = wire.selfNodeName.ifEmpty { null },
        ),
        window = window(wire.receiveTimeEpochSeconds.toLong()),
        limit = ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )
    if (target != null) {
        persistChannel(
            ChannelReactionRow(
                messageID = target.id,
                emoji = parsed.emoji,
                senderName = senderName,
                messageHash = parsed.messageHash,
                rawText = wire.text,
                channelIndex = wire.channelIndex,
                radioID = wire.radioID,
            ),
        )
        indexResolvedChannelMessage(target, wire.channelIndex, wire.selfNodeName)
        return true
    }
    reactionService.queuePendingReaction(parsed, wire.channelIndex, senderName, wire.text, wire.radioID)
    return true
}

private suspend fun ReactionHandler.handleMcoChannel(mco: ParsedMcoReaction, wire: ChannelReactionWire): Boolean {
    val senderName = wire.senderNodeName ?: unknownSenderLabel()
    val candidates = store.fetchChannelMessageCandidates(
        wire.radioID,
        wire.channelIndex,
        window(wire.receiveTimeEpochSeconds.toLong()),
        ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )
    val match = candidates.firstOrNull { candidate ->
        val candidateSender = channelCandidateSender(candidate, wire.selfNodeName)
        !ReactionParser.isReactionText(candidate.text, isDM = false) &&
            MeshCoreOpenReactionParser.computeReactionHash(
                candidate.reactionTimestamp,
                candidateSender,
                candidate.text,
            ) == mco.dartHash
    }
    if (match != null) {
        persistChannel(
            ChannelReactionRow(
                messageID = match.id,
                emoji = mco.emoji,
                senderName = senderName,
                messageHash = mco.dartHash,
                rawText = wire.text,
                channelIndex = wire.channelIndex,
                radioID = wire.radioID,
            ),
        )
    }
    return true
}

private suspend fun ReactionHandler.handleMcoV1Channel(v1: ParsedMcoReactionV1, wire: ChannelReactionWire): Boolean {
    val senderName = wire.senderNodeName ?: unknownSenderLabel()
    val candidates = store.fetchChannelMessageCandidates(
        wire.radioID,
        wire.channelIndex,
        window(v1.timestampSeconds),
        ReactionConstants.CANDIDATE_FETCH_LIMIT,
    )
    val match = candidates.firstOrNull { candidate ->
        val candidateSender = channelCandidateSender(candidate, wire.selfNodeName)
        val senderMatches = candidateSender == null ||
            MeshCoreOpenReactionParser.dartStringHash(candidateSender) == v1.senderNameHash
        !ReactionParser.isReactionText(candidate.text, isDM = false) &&
            senderMatches &&
            MeshCoreOpenReactionParser.dartStringHash(candidate.text) == v1.textHash
    }
    if (match != null) {
        persistChannel(
            ChannelReactionRow(
                messageID = match.id,
                emoji = v1.emoji,
                senderName = senderName,
                messageHash = v1.messageIdHash,
                rawText = wire.text,
                channelIndex = wire.channelIndex,
                radioID = wire.radioID,
            ),
        )
    }
    return true
}

internal fun ReactionHandler.channelCandidateSender(candidate: MessageDto, selfNodeName: String): String? =
    if (candidate.isOutgoing) selfNodeName.ifEmpty { null } else candidate.senderNodeName
