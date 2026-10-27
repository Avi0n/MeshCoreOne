// AndroidOnly: WP-407 Bind the real WP-203 codec/atomic restore to the existing WP-318 UI contract.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.data.backup.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.BackupContract
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.settings.app.backup.BackupEngine
import com.meshcoreone.android.feature.settings.app.backup.BackupExport
import com.meshcoreone.android.feature.settings.app.backup.ParsedBackup
import com.meshcoreone.android.feature.settings.app.backup.BackupModelKind as UiKind
import com.meshcoreone.android.feature.settings.app.backup.BackupManifest as UiManifest
import com.meshcoreone.android.feature.settings.app.backup.ImportResult as UiImport
import com.meshcoreone.android.feature.settings.app.backup.PerTypeCounts as UiCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CommittedAppBackup(val result: UiImport, val failure: Throwable, val preferences: CommittedBackupPreferenceException?)

internal class ParsedAppBackup(val envelope: AppBackupEnvelope) : ParsedBackup {
    override val exportDate get() = envelope.exportDate
    override val appVersion get() = envelope.appVersion
    override val appBuild get() = envelope.appBuild
    override val manifest get() = envelope.manifest.forUi()
}

internal class AppBackupFeature(
    private val service: AppBackupService,
    private val store: RoomPersistenceStore,
    private val restored: suspend (UiImport) -> Unit,
) : BackupEngine {
    private val mutableCommitted = MutableStateFlow<CommittedAppBackup?>(null)
    val committed: kotlinx.coroutines.flow.StateFlow<CommittedAppBackup?> = mutableCommitted
    fun acknowledge() {
        check(committed.value?.preferences == null) { "Preference completion must be retried before acknowledging" }
        mutableCommitted.value = null
    }
    private val operation = Mutex()
    override suspend fun export(): BackupExport = withContext(Dispatchers.IO) {
        service.export(store).let { BackupExport(it.data.toByteArray(), it.manifest.forUi()) }
    }

    override suspend fun parse(data: ByteArray): ParsedBackup = withContext(Dispatchers.IO) {
        BackupContract.validateCompressedSize(data.size.toLong())
        val caller = currentCoroutineContext()
        ParsedAppBackup(AppBackupCodec().parseBackup(data.inputStream(), checkCancellation = caller::ensureActive))
    }

    override suspend fun importBackup(backup: ParsedBackup): UiImport = operation.withLock {
        require(backup is ParsedAppBackup) { "Backup belongs to a different engine" }
        check(committed.value == null) { "A committed backup outcome must be acknowledged before another restore" }
        var completed: UiImport? = null
        try {
            // The store dispatches its own IO. An outer dispatcher hop can discard an already committed result.
            val result = service.importBackup(backup.envelope, store).forUi()
            completed = result
            // Return the committed result even if the caller cancelled during commit or refresh.
            withContext(NonCancellable) { restored(result) }
            result
        } catch (failure: CommittedBackupPreferenceException) {
            val result = failure.result.forUi()
            mutableCommitted.value = CommittedAppBackup(result, failure, failure)
            try { withContext(NonCancellable) { restored(result) } }
            catch (effects: Exception) { failure.addSuppressed(effects) }
            throw failure
        } catch (cancelled: CancellationException) {
            val result = completed ?: throw cancelled
            val failure = IllegalStateException("Backup data committed; post-restore refresh cancelled", cancelled)
            mutableCommitted.value = CommittedAppBackup(result, failure, null)
            throw failure
        } catch (problem: Exception) {
            val result = completed ?: throw problem
            val failure = IllegalStateException("Backup data committed; post-restore refresh failed", problem)
            mutableCommitted.value = CommittedAppBackup(result, failure, null)
            throw failure
        }
    }

    suspend fun completePreferences() = operation.withLock {
        val previous = requireNotNull(committed.value)
        val failure = requireNotNull(previous.preferences)
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val result = service.completePreferences(failure).forUi()
                restored(result.copy(counts = emptyMap(), channelSlotsAffectedByImport = emptyMap()))
                mutableCommitted.value = null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { mutableCommitted.value = previous.copy(failure = problem); throw problem }
    }

    private fun ImportResult.forUi() = UiImport(
            counts = counts.entries.associate { (kind, count) ->
                UiKind.valueOf(kind.name) to UiCounts(
                    Math.toIntExact(count.inserted), Math.toIntExact(count.merged),
                    Math.toIntExact(count.skipped), Math.toIntExact(count.dropped),
                )
            },
            settingsRestored = userDefaultsRestored,
            channelSlotsAffectedByImport = channelSlotsAffectedByImport.entries.associate {
                it.key.value to it.value.map { index -> index.toInt() }.toSet()
            },
        )
}

private fun BackupManifest.forUi() = UiManifest(UiKind.entries.associateWith {
    Math.toIntExact(count(BackupModelKind.valueOf(it.name)))
})
