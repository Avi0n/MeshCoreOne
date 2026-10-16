package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.model.toUppercaseHex

internal fun MutableStateFlow<NodeDiscoveryState>.appendNodeDiscoveryResult(
    namesByKeyHex: Map<String, String>,
    response: DiscoverResponse,
    filter: NodeDiscoveryFilter,
    unknownNodeLabel: String,
    receivedAtEpochSeconds: Long,
) {
    val hex = response.publicKey.toUppercaseHex()
    val row = NodeDiscoveryResult(
        name = namesByKeyHex[hex] ?: unknownDiscoveredNodeName(unknownNodeLabel, response.publicKey),
        publicKey = response.publicKey,
        nodeType = response.nodeType,
        snr = response.snr,
        snrIn = response.snrIn,
        rssi = response.rssi,
        scanFilter = filter,
        receivedAtEpochSeconds = receivedAtEpochSeconds,
    )
    update { current ->
        val without = current.results.filterNot {
            it.publicKey.contentEquals(response.publicKey) && it.scanFilter == filter
        }
        current.copy(results = without + row)
    }
}
