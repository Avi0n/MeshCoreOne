// AndroidOnly: WP-318 Feature-owned seams (contracts.md XFeatureDependencies pattern); the app binds them to the backup engine, the
// Storage Access Framework pickers and the connection/draft/blocked-cache owners. No core:services edge and no SAF dependency here.
package com.meshcoreone.android.feature.settings.app.backup

import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/**
 * Export, parse and atomic restore (WP-203 codec/restore behind it).
 * Pre-commit cancellation/failure rolls back. After commit, finish preference/process effects and
 * return the committed result despite caller cancellation; genuine completion failures remain explicit.
 * A committed preference failure retains its receipt and retries preferences only, never the rows.
 */
interface BackupEngine {
    suspend fun export(): BackupExport

    /** Decompresses, decodes and validates; throws `AppBackupException` (core:model) for format problems. */
    suspend fun parse(data: ByteArray): ParsedBackup

    suspend fun importBackup(backup: ParsedBackup): ImportResult
}

/** A document the user picked; reading may fail later if the grant was revoked or the provider went away. */
interface BackupSource {
    /** Size reported by the provider, when it has one; checked against the size cap before reading. */
    val sizeBytes: Long?
    suspend fun readBytes(): ByteArray
}

sealed interface BackupPickOutcome {
    data class Picked(val source: BackupSource) : BackupPickOutcome
    data object Cancelled : BackupPickOutcome
}

sealed interface BackupSaveOutcome {
    data class Saved(val displayName: String) : BackupSaveOutcome
    data object Cancelled : BackupSaveOutcome
}

/** Failures the document layer reports (the cause keeps the platform message, e.g. ENOSPC text). */
sealed class BackupDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The picked URI no longer resolves (provider removed, file deleted). */
    class UriUnavailable(cause: Throwable? = null) : BackupDocumentException("Backup document is unavailable", cause)

    /** The persisted/one-shot read grant was revoked. */
    class PermissionRevoked(cause: Throwable? = null) : BackupDocumentException("Backup document permission revoked", cause)

    /** The target volume has no room for the backup. */
    class InsufficientSpace(cause: Throwable? = null) : BackupDocumentException(cause?.message ?: "Insufficient storage space", cause)

    class WriteFailed(cause: Throwable? = null) : BackupDocumentException(cause?.message ?: "Write failed", cause)
}

/** System file picker slot (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT); returns outcomes, never leaves a half-written state. */
interface BackupDocumentPort {
    /** Asks the user where to save and writes [data]; [BackupSaveOutcome.Cancelled] when the picker was dismissed. */
    suspend fun save(suggestedName: String, data: ByteArray): BackupSaveOutcome

    suspend fun pick(): BackupPickOutcome
}

/** Radio connection gate: import is refused while connected. */
interface BackupConnectionGate {
    val isRadioConnected: StateFlow<Boolean>
}

/** Follow-ups after a restore wrote directly to the store (the sync-path callbacks were bypassed). */
interface BackupImportEffects {
    /** Bumps contacts/conversations versions so mounted tabs reload. */
    fun restoredDataChanged()

    /** Clears chat drafts for channel slots whose occupant changed. */
    fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>)

    /** Re-reads blocked contacts/senders that the incoming-message filter cached at connect time. */
    suspend fun refreshBlockedContactsCache()
}

fun interface BackupDiagnostics {
    fun report(message: String, failure: Throwable?)
}

/** Everything the backup holder needs, bundled for the app binding. */
class BackupFeatureDependencies(
    val engine: BackupEngine,
    val documents: BackupDocumentPort,
    val connection: BackupConnectionGate,
    val effects: BackupImportEffects,
    val diagnostics: BackupDiagnostics,
    val strings: BackupStrings,
)
