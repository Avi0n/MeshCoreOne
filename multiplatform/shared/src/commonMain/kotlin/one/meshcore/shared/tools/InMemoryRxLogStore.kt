package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid

class InMemoryRxLogStore(private val maxEntries: Int = MAX_RX_LOG_ENTRIES) : RxLogStore {
    private val mutex = Mutex()
    private val entries = mutableListOf<RxLogEntry>()
    private val _snapshot = MutableStateFlow<List<RxLogEntry>>(emptyList())
    override val snapshot: StateFlow<List<RxLogEntry>> = _snapshot.asStateFlow()

    override suspend fun list(radioID: Uuid): List<RxLogEntry> = mutex.withLock {
        entries.filter { it.radioID == radioID }
    }

    override suspend fun clear(radioID: Uuid) = mutex.withLock {
        entries.removeAll { it.radioID == radioID }
        publishLocked()
    }

    override suspend fun append(entry: RxLogEntry) = mutex.withLock {
        entries.add(0, entry)
        val forRadio = entries.withIndex().filter { it.value.radioID == entry.radioID }
        if (forRadio.size > maxEntries) {
            val drop = forRadio.drop(maxEntries).map { it.index }.toSet()
            // Remove highest indices first so earlier removals do not shift later ones.
            drop.sortedDescending().forEach { entries.removeAt(it) }
        }
        publishLocked()
    }

    override suspend fun redecrypt(decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> = mutex.withLock {
        val changed = mutableListOf<RxLogEntry>()
        // One batch for the whole held log: a late secret re-derives every row at once, and the
        // protocol implementation is a boundary crossing away.
        redecryptRxLogEntries(entries, decode).forEachIndexed { index, updated ->
            if (updated.decodeDiffers(entries[index])) {
                entries[index] = updated
                changed += updated
            }
        }
        if (changed.isNotEmpty()) publishLocked()
        changed
    }

    /** Whether a re-derived row differs from the held one in any decrypt or region field. */
    private fun RxLogEntry.decodeDiffers(other: RxLogEntry): Boolean = decryptStatus != other.decryptStatus ||
        decodedText != other.decodedText ||
        channelIndex != other.channelIndex ||
        channelName != other.channelName ||
        senderTimestamp != other.senderTimestamp ||
        regionScope != other.regionScope ||
        regionScopeMatches != other.regionScopeMatches

    private fun publishLocked() {
        _snapshot.value = entries.toList()
    }
}
