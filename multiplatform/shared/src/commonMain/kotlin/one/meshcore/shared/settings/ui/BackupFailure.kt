package one.meshcore.shared.settings.ui

/** A backup failure and which half of the screen raised it; the two differ only in fallback copy. */
internal data class BackupFailure(val cause: Throwable, val isExport: Boolean = false)
