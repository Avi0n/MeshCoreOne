package one.meshcore.shared.contact

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid

/** In-memory discovered-node store for ingest, retention, and view-model tests. */
class InMemoryDiscoveredNodeStore(private val maxPerRadio: Int = MAX_DISCOVERED_NODES_PER_RADIO) :
    DiscoveredNodeStore {
    private val mutex = Mutex()
    private val nodes = mutableListOf<DiscoveredNodeRecord>()
    private val pendingInboundHops = PendingInboundHopBuffer()
    private val _snapshot = MutableStateFlow<List<DiscoveredNodeRecord>>(emptyList())
    override val snapshot: StateFlow<List<DiscoveredNodeRecord>> = _snapshot.asStateFlow()

    override suspend fun list(radioID: Uuid): List<DiscoveredNodeRecord> = mutex.withLock {
        nodes.filter { it.radioID == radioID }.sortedByDescending { it.lastHeardEpochSeconds }
    }

    override suspend fun clear(radioID: Uuid) = mutex.withLock {
        nodes.removeAll { it.radioID == radioID }
        publishLocked()
    }

    override suspend fun upsert(node: DiscoveredNodeRecord): Boolean = mutex.withLock {
        val index = nodes.indexOfFirst {
            it.radioID == node.radioID && it.publicKey.contentEquals(node.publicKey)
        }
        val isNew = index < 0
        val existing = if (isNew) null else nodes[index]
        val row = node.withCarriedInboundHop(
            existingId = existing?.id,
            storedHops = existing?.inboundHopCount,
            storedTimestamp = existing?.inboundHopAdvertTimestamp,
            drained = pendingInboundHops.drain(node.radioID, node.publicKey),
        )
        if (isNew) {
            nodes.add(row)
            trimLocked(row.radioID)
        } else {
            nodes[index] = row
        }
        publishLocked()
        isNew
    }

    override suspend fun setInboundHopCount(
        radioID: Uuid,
        publicKey: ByteArray,
        hopCount: Int,
        advertTimestamp: Long?,
    ) = mutex.withLock {
        val index = nodes.indexOfFirst { it.radioID == radioID && it.publicKey.contentEquals(publicKey) }
        if (index < 0) {
            pendingInboundHops.buffer(radioID, publicKey, hopCount, advertTimestamp)
            return@withLock
        }
        val current = nodes[index]
        val adopted = adoptInboundHop(
            storedHops = current.inboundHopCount,
            storedTimestamp = current.inboundHopAdvertTimestamp,
            incomingHops = hopCount,
            incomingTimestamp = advertTimestamp,
        ) ?: return@withLock
        nodes[index] = current.copy(
            inboundHopCount = adopted.hopCount,
            inboundHopAdvertTimestamp = adopted.advertTimestamp,
        )
        publishLocked()
    }

    override suspend fun remove(radioID: Uuid, publicKey: ByteArray) = mutex.withLock {
        nodes.removeAll { it.radioID == radioID && it.publicKey.contentEquals(publicKey) }
        publishLocked()
    }

    private fun trimLocked(radioID: Uuid) {
        val forRadio = nodes.withIndex().filter { it.value.radioID == radioID }
            .sortedByDescending { it.value.lastHeardEpochSeconds }
        if (forRadio.size <= maxPerRadio) return
        val dropIds = forRadio.drop(maxPerRadio).map { it.value.id }.toSet()
        nodes.removeAll { it.id in dropIds }
    }

    private fun publishLocked() {
        _snapshot.value = nodes.toList()
    }
}
