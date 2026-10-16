package one.meshcore.shared.persistence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.MAX_DISCOVERED_NODES_PER_RADIO
import one.meshcore.shared.contact.PendingInboundHopBuffer
import one.meshcore.shared.contact.adoptInboundHop
import one.meshcore.shared.contact.withCarriedInboundHop
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [DiscoveredNodeStore]. Passively discovered nodes survive an app restart and can be exported
 * in a backup, matching iOS (SwiftData `DiscoveredNode`). Upsert dedups by
 * `(radioID, publicKey)`, preserving the existing surrogate id; a fresh insert trims the radio to
 * [maxPerRadio], dropping the oldest by `lastHeard`. [snapshot] mirrors the persisted rows so
 * StateFlow-observing UI updates live, and a [Mutex] serializes the fetch/write windows.
 */
class RoomDiscoveredNodeStore(
    private val dao: DiscoveredNodeDao,
    private val maxPerRadio: Int = MAX_DISCOVERED_NODES_PER_RADIO,
) : DiscoveredNodeStore {
    private val mutex = Mutex()
    private val pendingInboundHops = PendingInboundHopBuffer()
    private val _snapshot = MutableStateFlow<List<DiscoveredNodeRecord>>(emptyList())
    override val snapshot: StateFlow<List<DiscoveredNodeRecord>> = _snapshot.asStateFlow()

    /** Loads persisted rows into [snapshot] so restored/restarted rows appear before the first advert. */
    suspend fun refresh() = mutex.withLock { publishLocked() }

    override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> = mutex.withLock {
        dao.listByRadio(radioID).map { it.toRecord() }.sortedByDescending { it.lastHeardEpochSeconds }
    }

    override suspend fun clear(radioID: Uuid) = mutex.withLock {
        dao.deleteByRadio(radioID)
        publishLocked()
    }

    override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = mutex.withLock {
        val existing = dao.find(node.radioID, node.publicKey)
        val isNew = existing == null
        val row = node.withCarriedInboundHop(
            existingId = existing?.id,
            storedHops = existing?.inboundHopCount,
            storedTimestamp = existing?.inboundHopAdvertTimestamp,
            drained = pendingInboundHops.drain(node.radioID, node.publicKey),
        )
        dao.upsert(row.toEntity())
        if (isNew) trimLocked(node.radioID)
        publishLocked()
        isNew
    }

    override suspend fun setInboundHopCount(
        radioID: Uuid,
        publicKey: ByteArray,
        hopCount: Int,
        advertTimestamp: Long?,
    ) = mutex.withLock {
        val existing = dao.find(radioID, publicKey)
        if (existing == null) {
            pendingInboundHops.buffer(radioID, publicKey, hopCount, advertTimestamp)
            return@withLock
        }
        val adopted = adoptInboundHop(
            storedHops = existing.inboundHopCount,
            storedTimestamp = existing.inboundHopAdvertTimestamp,
            incomingHops = hopCount,
            incomingTimestamp = advertTimestamp,
        ) ?: return@withLock
        dao.upsert(
            existing.copy(inboundHopCount = adopted.hopCount, inboundHopAdvertTimestamp = adopted.advertTimestamp),
        )
        publishLocked()
    }

    override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = mutex.withLock {
        dao.delete(radioID, publicKey)
        publishLocked()
    }

    private suspend fun trimLocked(radioID: Uuid) {
        val rows = dao.listByRadio(radioID)
        if (rows.size <= maxPerRadio) return
        val dropIds = rows.sortedByDescending { it.lastHeardEpochSeconds }.drop(maxPerRadio).map { it.id }
        if (dropIds.isNotEmpty()) dao.deleteByIds(dropIds)
    }

    private suspend fun publishLocked() {
        _snapshot.value = dao.listAll().map { it.toRecord() }
    }
}
