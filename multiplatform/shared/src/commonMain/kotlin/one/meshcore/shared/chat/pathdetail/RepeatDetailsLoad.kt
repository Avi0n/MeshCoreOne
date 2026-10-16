package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.runExceptionFallback

/**
 * Loads heard-repeat rows for the repeat-details sheet. A persist fault lands an empty list so the
 * sheet cannot stay on a spinner. iOS `HeardRepeatsService.refreshRepeats` returns `[]` on store error.
 */
suspend fun loadRepeatDetails(fetch: suspend () -> List<MessageRepeatDto>): List<MessageRepeatDto> =
    runExceptionFallback({ emptyList() }, fetch)
