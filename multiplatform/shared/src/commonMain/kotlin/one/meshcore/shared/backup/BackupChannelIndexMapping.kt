package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid

/**
 * Rewrites channel-message and reaction slots after [ChannelImportReconciler] placement, and drops
 * children whose channel had no free local slot. Mirrors Swift
 * `PersistenceStore.applyChannelIndexMapping`.
 */
object BackupChannelIndexMapping {
    data class Result(
        val messages: List<MessageDto>,
        val reactions: List<ReactionDto>,
        val messagesDropped: Int,
        val reactionsDropped: Int,
    )

    fun apply(
        messages: List<MessageDto>,
        reactions: List<ReactionDto>,
        channelIndexRemap: Map<Uuid, Map<Int, Int>>,
        droppedChannelIndices: Map<Uuid, Set<Int>>,
    ): Result {
        if (channelIndexRemap.isEmpty() && droppedChannelIndices.isEmpty()) {
            return Result(messages, reactions, messagesDropped = 0, reactionsDropped = 0)
        }

        val keptMessages = mutableListOf<MessageDto>()
        var messagesDropped = 0
        for (dto in messages) {
            val index = dto.channelIndex
            if (index != null && droppedChannelIndices[dto.radioID]?.contains(index) == true) {
                messagesDropped++
                continue
            }
            keptMessages += remapMessage(dto, channelIndexRemap)
        }

        val keptReactions = mutableListOf<ReactionDto>()
        var reactionsDropped = 0
        for (dto in reactions) {
            val index = dto.channelIndex
            if (index != null && droppedChannelIndices[dto.radioID]?.contains(index) == true) {
                reactionsDropped++
                continue
            }
            keptReactions += remapReaction(dto, channelIndexRemap)
        }

        return Result(
            messages = keptMessages,
            reactions = keptReactions,
            messagesDropped = messagesDropped,
            reactionsDropped = reactionsDropped,
        )
    }

    private fun remapMessage(dto: MessageDto, remap: Map<Uuid, Map<Int, Int>>): MessageDto {
        val backupIndex = dto.channelIndex ?: return dto
        val localIndex = remap[dto.radioID]?.get(backupIndex) ?: return dto
        val dedup = dto.deduplicationKey?.let {
            BackupMerge.rewriteChannelDeduplicationKey(it, backupIndex, localIndex)
        }
        return dto.copy(channelIndex = localIndex, deduplicationKey = dedup)
    }

    private fun remapReaction(dto: ReactionDto, remap: Map<Uuid, Map<Int, Int>>): ReactionDto {
        val backupIndex = dto.channelIndex ?: return dto
        val localIndex = remap[dto.radioID]?.get(backupIndex) ?: return dto
        return dto.copy(channelIndex = localIndex)
    }
}
