package one.meshcore.shared.tools

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.ChannelListSink
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.settings.PersistentLogger

/**
 * Collects [MeshEvent.RxLogData] into [RxLogCollectorInputs.store] for the live radio. Cancel and
 * restart when the session identity changes. [RxLogCollectorInputs.decodeCache] supplies channel
 * secrets and known-region keys. Optional [RxLogCollectorInputs.onEntryAppended] runs after each
 * successful append (e.g. [one.meshcore.shared.chat.pathdetail.HeardRepeatsService] correlation)
 * so domain consumers see the same decrypted row the Tools → RX Log list shows. Optional
 * [RxLogCollectorInputs.onRowsRederived] runs with the rows a late secret or region changed, which
 * is what lets a message stored before its region resolved be stamped after the fact.
 */
class RxLogEventCollector(private val inputs: RxLogCollectorInputs) {
    val channelListSink: ChannelListSink get() = inputs.decodeCache

    suspend fun updateKnownRegions(regions: List<String>) {
        inputs.decodeCache.updateKnownRegions(regions)
    }

    suspend fun collect(events: kotlinx.coroutines.flow.Flow<MeshEvent>): Unit = coroutineScope {
        // Secrets, DM keys and known regions land after packets are already logged, and plaintext is
        // never stored, so every material change re-derives the rows the store already holds.
        val redecrypt = launch {
            inputs.decodeCache.material.collect { material ->
                // The re-derived rows are the only notice a late secret or region gives, so they are
                // handed on rather than dropped: a message stored before its region resolved is stamped
                // from here or never.
                val rederived = redecryptGuarded(material)
                notifyRowsRederived(rederived, inputs.onRowsRederived, inputs.logger)
            }
        }
        events.collect { event ->
            if (event !is MeshEvent.RxLogData) return@collect
            val radioID = inputs.radioId() ?: return@collect
            val decode = inputs.decodeCache.snapshot()
            val entry = processRxLogEntry(
                radioID = radioID,
                data = event.data,
                receivedAtEpochMs = inputs.nowEpochMs(),
                decode = decode,
            )
            appendGuarded(entry)
            notifyEntryAppended(entry, inputs.onEntryAppended, inputs.logger)
        }
        redecrypt.cancel()
    }

    private suspend fun appendGuarded(entry: RxLogEntry) {
        runRxLogPersist(
            fallback = Unit,
            failure = "Failed to save RX log entry",
            log = { line -> inputs.logger?.error(line) },
        ) {
            inputs.store.append(entry)
        }
    }

    private suspend fun redecryptGuarded(material: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> = runRxLogPersist(
        fallback = emptyList(),
        failure = "Failed to re-process noMatchingKey entries",
        log = { line -> inputs.logger?.error(line) },
    ) {
        val updated = inputs.store.redecrypt(material)
        if (updated.isNotEmpty()) {
            inputs.logger?.info(successfullyReprocessedEntriesLine(updated.size))
        }
        updated
    }

    /** A sink fault is logged so RX ingest keeps collecting. */
    private suspend fun notifyEntryAppended(
        entry: RxLogEntry,
        onEntryAppended: suspend (RxLogEntry) -> Unit,
        logger: PersistentLogger?,
    ) {
        runRxLogPersist(
            fallback = Unit,
            failure = FAILED_NOTIFY_RX_LOG_ENTRY,
            log = { line -> logger?.error(line) },
        ) {
            onEntryAppended(entry)
        }
    }
}
