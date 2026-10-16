package one.meshcore.shared.connection

/**
 * iOS `SyncCoordinator` dump lines for a thrown sync phase. The connect path swallows the throw so the
 * other phases still run; the export must still carry the reason.
 */
internal fun syncPhaseFailLine(phase: String, reason: String): String = when (phase) {
    PHASE_CONTACTS -> "[Sync] State → .failed: $reason"
    PHASE_CHANNELS -> "[Sync] Phase end: channels partial after thrown error: $reason"
    PHASE_MESSAGES -> "[Sync] Message polling failed after contacts/channels: $reason"
    else -> "[Sync] $phase failed: $reason"
}

internal const val PHASE_CONTACTS: String = "contacts"
internal const val PHASE_CHANNELS: String = "channels"
internal const val PHASE_MESSAGES: String = "messages"
