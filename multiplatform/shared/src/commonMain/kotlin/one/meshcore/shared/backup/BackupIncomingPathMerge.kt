package one.meshcore.shared.backup

import one.meshcore.shared.model.MessageDirection

/** Preserves distinct incoming flood arrivals when a backup message matches a local message. */
object BackupIncomingPathMerge {
    data class Result(
        val parent: MessageDto,
        val promotedRepeats: List<MessageRepeatDto>,
        val repeatsToImport: List<MessageRepeatDto>,
        val skippedRepeats: Int,
    )

    fun plan(
        parent: MessageDto,
        skippedParents: List<MessageDto>,
        existingRepeats: List<MessageRepeatDto>,
        incomingRepeats: List<MessageRepeatDto>,
    ): Result {
        if (parent.direction != MessageDirection.INCOMING) {
            return Result(parent, emptyList(), incomingRepeats, 0)
        }

        var updatedParent = parent
        val knownExtraPaths = existingRepeats.map { it.pathNodes }.toMutableList()
        val promoted = mutableListOf<MessageRepeatDto>()
        for (foreign in skippedParents) {
            val path = foreign.pathNodes ?: continue
            val canonical = updatedParent.pathNodes
            if (canonical == null) {
                updatedParent = updatedParent.copy(pathNodes = path, pathLength = foreign.pathLength)
            } else if (!canonical.contentEquals(path) && knownExtraPaths.none { it.contentEquals(path) }) {
                knownExtraPaths.add(path)
                promoted.add(
                    MessageRepeatDto(
                        id = newLocalId(),
                        messageID = parent.id,
                        receivedAt = foreign.createdAt,
                        pathNodes = path,
                        pathLength = foreign.pathLength,
                        snr = foreign.snr,
                    ),
                )
            }
        }

        val repeatsToImport = mutableListOf<MessageRepeatDto>()
        var skipped = 0
        for (repeat in incomingRepeats) {
            val path = repeat.pathNodes
            if (updatedParent.pathNodes?.contentEquals(path) == true ||
                knownExtraPaths.any { it.contentEquals(path) }
            ) {
                skipped++
            } else {
                knownExtraPaths.add(path)
                repeatsToImport.add(repeat)
            }
        }
        return Result(updatedParent, promoted, repeatsToImport, skipped)
    }
}
