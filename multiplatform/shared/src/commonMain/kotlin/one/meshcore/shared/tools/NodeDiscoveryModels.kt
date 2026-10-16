package one.meshcore.shared.tools

import one.meshcore.shared.model.toUppercaseHex

/** Tools → Node Discovery segment: active scan filter (firmware type mask). */
enum class NodeDiscoveryFilter(val filterValue: Int) {
    REPEATERS(0x04),
    SENSORS(0x10),
}

/** Sort order for active discovery results. */
enum class NodeDiscoverySortOrder {
    SNR,
    NAME,
}

/** One active-scan hit (or updated hit) for a public key under the current filter. */
data class NodeDiscoveryResult(
    val name: String,
    val publicKey: ByteArray,
    val nodeType: Int,
    val snr: Double,
    val snrIn: Double,
    val rssi: Int,
    val scanFilter: NodeDiscoveryFilter,
    val receivedAtEpochSeconds: Long,
) {
    val publicKeyHex: String get() = publicKey.toUppercaseHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeDiscoveryResult) return false
        return publicKey.contentEquals(other.publicKey) &&
            scanFilter == other.scanFilter &&
            name == other.name &&
            nodeType == other.nodeType &&
            snr == other.snr &&
            snrIn == other.snrIn &&
            rssi == other.rssi &&
            receivedAtEpochSeconds == other.receivedAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + scanFilter.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + nodeType
        result = 31 * result + snr.hashCode()
        result = 31 * result + snrIn.hashCode()
        result = 31 * result + rssi
        result = 31 * result + receivedAtEpochSeconds.hashCode()
        return result
    }
}

data class NodeDiscoveryState(
    val results: List<NodeDiscoveryResult> = emptyList(),
    val filter: NodeDiscoveryFilter = NodeDiscoveryFilter.REPEATERS,
    val sortOrder: NodeDiscoverySortOrder = NodeDiscoverySortOrder.SNR,
    val isScanning: Boolean = false,
    val hasScannedOnce: Boolean = false,
    val errorMessage: String? = null,
    val addError: one.meshcore.shared.contact.AddContactError? = null,
    val addedPublicKeyHexes: Set<String> = emptySet(),
    val addingPublicKeyHex: String? = null,
) {
    val sortedResults: List<NodeDiscoveryResult>
        get() {
            val filtered = results.filter { it.scanFilter == filter }
            return when (sortOrder) {
                NodeDiscoverySortOrder.SNR -> filtered.sortedByDescending { it.snr }
                NodeDiscoverySortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
            }
        }

    fun isAdded(result: NodeDiscoveryResult): Boolean = result.publicKeyHex in addedPublicKeyHexes
}
