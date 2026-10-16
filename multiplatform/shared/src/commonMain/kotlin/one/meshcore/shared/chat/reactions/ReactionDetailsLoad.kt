package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.runExceptionFallback

/**
 * Loads reaction rows for the who-reacted sheet. A persist fault lands an empty list so the sheet
 * cannot stay on a spinner. iOS `ReactionDetailsSheet.loadReactions` clears `isLoading` after catch.
 */
suspend fun loadReactionDetails(fetch: suspend () -> List<ReactionDto>): List<ReactionDto> =
    runExceptionFallback({ emptyList() }, fetch)
