package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.BackupExportSuccessSummary
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.BackupUserDefaults
import one.meshcore.shared.backup.RoomBackupExporter
import one.meshcore.shared.backup.RoomBackupImporter
import one.meshcore.shared.backup.adoptImportedRadioForOfflineScope
import one.meshcore.shared.backup.adoptRadioAfterCommittedImport
import one.meshcore.shared.backup.backupExportSuccessSummary
import one.meshcore.shared.backup.backupExportedLine
import one.meshcore.shared.backup.backupUserDefaultsSnapshot
import one.meshcore.shared.backup.clearAffectedChannelDrafts
import one.meshcore.shared.backup.decodeBackupEnvelope
import one.meshcore.shared.backup.restoreInto
import one.meshcore.shared.backup.restorePrefsAfterCommittedImport
import one.meshcore.shared.backup.zlibCompress
import one.meshcore.shared.settings.PersistentLogger

/**
 * Settings → Backup export: encode Room rows to uncompressed JSON for file export.
 */
suspend fun AppContainer.exportBackupJson(appVersion: String, appBuild: String): String = RoomBackupExporter.exportJson(
    database = database,
    appVersion = appVersion,
    appBuild = appBuild,
    exportDate = System.currentTimeMillis() / AppContainer.MILLIS_PER_SECOND,
    userDefaults = prefs.backupUserDefaultsSnapshot(),
)

internal suspend fun AppContainer.exportBackupDocument(
    writeDocument: suspend (ByteArray) -> String?,
): BackupExportSuccessSummary? {
    val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    val json = exportBackupJson(
        appVersion = packageInfo.appVersionNameOrMissing(),
        appBuild = packageInfo.appBuildNumber(),
    )
    val jsonBytes = json.encodeToByteArray()
    val compressed = zlibCompress(jsonBytes)
    recordBackupExport(jsonBytes.size, compressed.size)
    val filename = writeDocument(compressed) ?: return null
    return backupExportSuccessSummary(filename, compressed, json)
}

/** iOS `AppBackupService` export dump after zlib wrap. */
fun AppContainer.recordBackupExport(jsonBytes: Int, compressedBytes: Int) {
    PersistentLogger(appScope, category = "AppBackupService")
        .info(backupExportedLine(jsonBytes, compressedBytes))
}

/** Secondary paste path: uncompressed JSON envelope. */
suspend fun AppContainer.importBackupJson(jsonText: String): BackupImportResult {
    val envelope = decodeBackupEnvelope(jsonText)
    val result = RoomBackupImporter.importEnvelope(
        database,
        envelope,
        radioConnected = { connectionManager.state.value.isConnected },
    )
    // Room already has the restore; cancellation must not report that nothing changed.
    return withContext(NonCancellable) {
        // A restore can move a channel to a different slot, and a draft is keyed by slot: without this the
        // draft typed against the old channel would follow the slot to a different one.
        withContext(Dispatchers.Main.immediate) { result.clearAffectedChannelDrafts(draftEditors) }
        // Import writes discovered-node rows straight to Room, bypassing the store's published snapshot;
        // refresh so restored Discover rows appear without a restart.
        discoveredNodeStoreImpl.refresh()
        val prefsRestored = restorePrefsAfterCommittedImport {
            restoreBackupUserDefaults(envelope.userDefaults)
        }
        val finished = result.copy(userDefaultsRestored = prefsRestored)
        // A first-install restore has no last-connected radio, so imported chats stay unseen
        // until this adopts the imported partition. Desktop already calls the same helper.
        adoptRadioAfterCommittedImport {
            adoptImportedRadioForOfflineScope(database, lastConnectionStore, settingsLogger::warning)
        }
        try {
            offlineData.resetAfterDataRestore()
        } catch (_: CancellationException) {
            // Rows are already visible on the next launch if this refresh is cut short.
        }
        // Import writes Room directly, bypassing the sync-path list bumps. Reload mounted
        // Chats and Nodes the way iOS `notifyDataRestored` bumps contacts/conversations versions.
        if (finished.hasRestoredChanges) {
            try {
                notifyConversationsChanged()
            } catch (_: CancellationException) {
                // Lists reload on the next connection or launch.
            }
        }
        finished
    }
}

/**
 * Applies the envelope's preference family write-if-missing, then reseeds the live preference caches
 * so a restored value takes effect without a process restart. The caches are only reseeded when the
 * restore actually wrote something, so an import into a fully configured device stays a no-op.
 */
internal suspend fun AppContainer.restoreBackupUserDefaults(userDefaults: BackupUserDefaults?): Boolean {
    if (userDefaults == null || userDefaults.restoreInto(prefs, settingsLogger::warning).isEmpty()) return false
    chatDisplayPreferences.load()
    translationTargetPreferences.load()
    appearancePreferences.load()
    mapDisplayPreferences.load()
    return true
}
