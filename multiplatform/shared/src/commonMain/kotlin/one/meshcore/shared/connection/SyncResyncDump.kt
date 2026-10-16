package one.meshcore.shared.connection

import one.meshcore.shared.model.Uuid

/** iOS `Initial sync did not produce usable contacts, starting resync loop`. */
const val INITIAL_SYNC_NO_USABLE_CONTACTS =
    "Initial sync did not produce usable contacts, starting resync loop"

/** iOS `Resync succeeded`. */
const val RESYNC_SUCCEEDED = "Resync succeeded"

/** iOS `Resync completed without usable contacts`. */
const val RESYNC_COMPLETED_WITHOUT_USABLE_CONTACTS = "Resync completed without usable contacts"

fun resyncAttemptLine(attempt: Int, maxAttempts: Int): String = "Resync attempt $attempt/$maxAttempts"

fun resyncFailedTimesLine(maxAttempts: Int): String = "Resync failed $maxAttempts times, disconnecting"

/** iOS `Attempting resync for device \(radioID)`. */
fun attemptingResyncLine(radioId: Uuid): String = "Attempting resync for device ${radioId.value}"

/** iOS `Resync failed: \(error.localizedDescription)`. */
fun resyncFailedLine(detail: String): String = "Resync failed: $detail"
