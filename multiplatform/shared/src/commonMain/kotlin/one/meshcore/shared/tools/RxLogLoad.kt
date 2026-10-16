package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** iOS `RxLogService` region back-fill persist dump. */
internal const val FAILED_REPROCESS_REGION_ENTRIES = "Failed to re-process region entries"

/** Sink fault after an RX log row is stored. Ingest keeps collecting. */
internal const val FAILED_NOTIFY_RX_LOG_ENTRY = "Failed to notify RX log entry"

/**
 * Runs one RX-log persist step. A Room fault is logged and [fallback] is returned so ingest and
 * Tools stay up. Cancellation still stops the caller.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runRxLogPersist(
    fallback: T,
    failure: String,
    log: (String) -> Unit,
    block: suspend () -> T,
): T {
    try {
        return block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        log("$failure: ${error.message ?: error}")
        return fallback
    }
}

/** iOS `Successfully re-processed \(updates.count) entries`. */
internal fun successfullyReprocessedEntriesLine(count: Int): String = "Successfully re-processed $count entries"

/**
 * Hands late-resolved rows to [onRowsRederived]. A persist fault is logged and swallowed so the
 * material collector stays alive. Cancellation is rethrown.
 */
internal suspend fun notifyRowsRederived(
    rows: List<RxLogEntry>,
    onRowsRederived: suspend (List<RxLogEntry>) -> Unit,
    logger: PersistentLogger?,
) {
    if (rows.isEmpty()) return
    runRxLogPersist(
        fallback = Unit,
        failure = FAILED_REPROCESS_REGION_ENTRIES,
        log = { line -> logger?.error(line) },
    ) {
        onRowsRederived(rows)
    }
}

/** iOS `Loaded \(channels.count) channel secrets from database`. */
internal fun loadedChannelSecretsLine(count: Int): String = "Loaded $count channel secrets from database"

/** iOS `Loaded \(publicKeys.count) contact public key prefixes from database`. */
internal fun loadedContactPublicKeyPrefixesLine(count: Int): String =
    "Loaded $count contact public key prefixes from database"

/**
 * Loads contact public keys for DM ECDH decrypt after Ready. A Room fault returns null so callers
 * preserve the last-known-good cache; a successful empty list intentionally clears it.
 */
suspend fun <T> loadContactsForRxLogDecrypt(fetch: suspend () -> List<T>, logger: PersistentLogger?): List<T>? =
    runRxLogPersist(
        fallback = null,
        failure = "Failed to load contact public keys",
        log = { line -> logger?.error(line) },
    ) {
        val loaded = fetch()
        if (loaded.isNotEmpty()) logger?.info(loadedContactPublicKeyPrefixesLine(loaded.size))
        loaded
    }

const val FAILED_EXPORT_PRIVATE_KEY = "Failed to export private key"
const val FAILED_LOAD_CHANNEL_SECRETS = "Failed to load channel secrets"

/** iOS `RxLogService.clearEntries` persist-fail dump. */
const val FAILED_CLEAR_RX_LOG_ENTRIES = "Failed to clear RX log entries"

/** iOS `RxLogService.loadExistingEntries` persist-fail dump. */
const val FAILED_LOAD_RX_LOG_ENTRIES = "Failed to load RX log entries"

/**
 * Runs [load] for the persisted RX-log page. A Room fault is logged so Tools → RX Log can stay
 * up with an empty list.
 */
suspend fun <T> loadExistingRxLogEntriesGuarded(logger: PersistentLogger?, load: suspend () -> T, empty: () -> T): T =
    runRxLogPersist(
        fallback = empty(),
        failure = FAILED_LOAD_RX_LOG_ENTRIES,
        log = { line -> logger?.error(line) },
    ) {
        load()
    }

/** Clears one radio's RX log. A Room fault is logged so Tools → Clear cannot crash the screen. */
suspend fun clearRxLogEntriesGuarded(store: RxLogStore, radioID: Uuid, logger: PersistentLogger?) {
    runRxLogPersist(
        fallback = Unit,
        failure = FAILED_CLEAR_RX_LOG_ENTRIES,
        log = { line -> logger?.error(line) },
    ) {
        store.clear(radioID)
    }
}

/** Exports the radio private key for RX-log DM decrypt. A fault is logged; Ready stays up. */
suspend fun exportPrivateKeyForRxLog(export: suspend () -> ByteArray, logger: PersistentLogger?): ByteArray? =
    runRxLogPersist(
        fallback = null,
        failure = FAILED_EXPORT_PRIVATE_KEY,
        log = { line -> logger?.warning(line) },
    ) {
        export()
    }

/**
 * Loads channel secrets for RX-log decrypt. A Room fault is logged and returns null so a launch
 * preload cannot wipe an already-warmed cache.
 */
suspend fun <T> loadChannelsForRxLogDecrypt(fetch: suspend () -> List<T>, logger: PersistentLogger?): List<T>? =
    runRxLogPersist(
        fallback = null,
        failure = FAILED_LOAD_CHANNEL_SECRETS,
        log = { line -> logger?.error(line) },
    ) {
        val loaded = fetch()
        if (loaded.isNotEmpty()) logger?.info(loadedChannelSecretsLine(loaded.size))
        loaded
    }
