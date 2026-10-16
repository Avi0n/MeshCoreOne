package one.meshcore.shared.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.DiscoverResponse
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.runReportingException

internal data class NodeDiscoveryScanInputs(
    val dependencies: NodeDiscoveryViewModel.Dependencies,
    val filter: NodeDiscoveryFilter,
    val durationMs: Long,
    val sessionChanges: StateFlow<Long>,
    val owns: () -> Boolean,
    val preloadNames: suspend () -> Unit,
    val armDeadline: () -> Unit,
    val onResponse: (DiscoverResponse) -> Unit,
    val onFailure: () -> Unit,
)

/** The ACK arms one deadline; a rebound event owner uses that request's tag and remaining window. */
internal suspend fun collectNodeDiscoveryScan(inputs: NodeDiscoveryScanInputs) {
    val caller = currentCoroutineContext()
    runReportingException(onFailure = { error ->
        caller.ensureActive()
        if (inputs.owns() && !isStoppedDiscoverySession(error)) inputs.onFailure()
    }) {
        inputs.preloadNames()
        caller.ensureActive()
        if (!inputs.owns()) return@runReportingException
        val tag = inputs.dependencies.sendDiscover(inputs.filter.filterValue, false)
        caller.ensureActive()
        if (!inputs.owns()) return@runReportingException
        val tagBytes = NodeDiscoveryViewModel.tagToLittleEndianBytes(tag)
        inputs.armDeadline()
        withTimeoutOrNull(inputs.durationMs) {
            collectDiscoveryResponses(inputs.sessionChanges, inputs.dependencies.events) { event ->
                if (inputs.owns() &&
                    event is MeshEvent.DiscoverResponseReceived &&
                    event.response.tag.contentEquals(tagBytes)
                ) {
                    inputs.onResponse(event.response)
                }
            }
        }
    }
}

/** A completed stream waits for a new owner signal; rebinding keeps the original request tag. */
internal suspend fun collectDiscoveryResponses(
    ownerChanges: StateFlow<Long>,
    events: () -> Flow<MeshEvent>?,
    onEvent: (MeshEvent) -> Unit,
) {
    ownerChanges.collectLatest {
        val owner = events() ?: return@collectLatest
        try {
            owner.collect { event ->
                currentCoroutineContext().ensureActive()
                if (events() === owner) onEvent(event)
            }
        } catch (error: MeshBridgeException) {
            if (!isStoppedDiscoverySession(error)) throw error
        }
    }
}

/** These frozen bridge codes represent a retired session, never a discovery timeout. */
internal fun isStoppedDiscoverySession(error: Throwable): Boolean = error is MeshBridgeException &&
    error.code in
    setOf(BridgeErrorCode.NOT_CONNECTED, BridgeErrorCode.CONNECTION_LOST, BridgeErrorCode.SESSION_NOT_STARTED)
