package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.remotenode.isAdmin

/** Acknowledged discovery retains its deadline while a hidden screen sends no further polls. */
@Suppress("TooGenericExceptionCaught")
fun NodeStatusViewModel.startDiscovery() {
    if (isClosed || !session.isAdmin || statusState.value.isDiscovering) return
    val service = dependencies.repeaterAdminService() ?: return
    val generation = ++discoveryGeneration
    discoverJob?.cancel()
    discoveryPollsEnabled = true
    discoveryDeadlineMillis = null
    statusState.update {
        it.copy(
            isDiscovering = true,
            discoverySecondsRemaining = NodeStatusViewModel.DISCOVERY_DURATION_SECONDS,
            neighborsSectionError = null,
        )
    }
    discoverJob = scope.launch {
        try {
            currentRemoteRequest({
                !isClosed && generation == discoveryGeneration && dependencies.repeaterAdminService() === service
            }) { service.sendCommand(session.id, NodeStatusViewModel.DISCOVER_NEIGHBORS_COMMAND) }
        } catch (cancelled: CancellationException) {
            if (scope.isActive && generation == discoveryGeneration) clearDiscovery()
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (generation != discoveryGeneration) return@launch
            clearDiscovery()
            statusState.update { it.copy(neighborsSectionError = error.toSectionError()) }
            return@launch
        }
        runDiscoveryCountdown(generation)
    }
}

private suspend fun NodeStatusViewModel.runDiscoveryCountdown(generation: Long) {
    discoveryDeadlineMillis = discoveryNowMillis +
        NodeStatusViewModel.DISCOVERY_DURATION_SECONDS * NodeStatusViewModel.DISCOVERY_TICK_MS
    var tickCount = 0
    while (generation == discoveryGeneration) {
        delay(NodeStatusViewModel.DISCOVERY_TICK_MS)
        currentCoroutineContext().ensureActive()
        if (generation != discoveryGeneration) return
        val deadline = discoveryDeadlineMillis ?: return
        val remainingMillis = (deadline - discoveryNowMillis).coerceAtLeast(0L)
        val remaining = (
            (remainingMillis + NodeStatusViewModel.DISCOVERY_TICK_MS - 1L) /
                NodeStatusViewModel.DISCOVERY_TICK_MS
            ).toInt()
        statusState.update { it.copy(discoverySecondsRemaining = remaining) }
        if (remaining == 0) break
        tickCount++
        if (discoveryPollsEnabled && tickCount % NodeStatusViewModel.DISCOVERY_POLL_INTERVAL_SECONDS == 0) {
            pollNeighborsDuringDiscovery(generation)
        }
    }
    if (generation == discoveryGeneration) clearDiscovery()
}

/** Pauses future polls while retaining a pending poll, its results and the countdown. */
fun NodeStatusViewModel.pauseDiscoveryPolls() {
    discoveryPollsEnabled = false
}

/** The visible Stop control retires the command and any pending poll. */
fun NodeStatusViewModel.stopDiscovery() {
    discoveryGeneration++
    discoverJob?.cancel()
    clearDiscovery()
}

internal fun NodeStatusViewModel.expireDiscoveryDeadline() {
    val deadline = discoveryDeadlineMillis ?: return
    if (!isClosed && discoveryNowMillis >= deadline) stopDiscovery()
}

@Suppress("TooGenericExceptionCaught")
private suspend fun NodeStatusViewModel.pollNeighborsDuringDiscovery(generation: Long) {
    val service = dependencies.repeaterAdminService() ?: return
    val isCurrent = {
        !isClosed && generation == discoveryGeneration && dependencies.repeaterAdminService() === service
    }
    try {
        val response = currentRemoteRequest(isCurrent) { service.fetchAllNeighbors(session.id) }
        applyNeighbors(response.neighbours, isCurrent)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (generation == discoveryGeneration && dependencies.repeaterAdminService() !== service) return
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (isCurrent()) statusState.update { it.copy(neighborsSectionError = error.toSectionError()) }
    }
}

internal suspend fun NodeStatusViewModel.applyNeighbors(
    neighbors: List<Neighbour>,
    isCurrent: () -> Boolean = {
        true
    },
) {
    val currentOwner = { !isClosed && isCurrent() }
    val baseline = currentRemoteRequest(currentOwner) { loadNeighborBaseline() }
    statusState.update {
        it.copy(
            neighbors = neighbors,
            neighborsLoaded = true,
            isLoadingNeighbors = false,
            neighborsSectionError = null,
            previousNeighborSnapshot = baseline?.previous,
            seenNeighborPrefixes = baseline?.seenPrefixes ?: emptySet(),
        )
    }
    currentRemoteRequest(currentOwner) { recordNeighbors(neighbors) }
    snapshotsRecorded()
}

private fun NodeStatusViewModel.clearDiscovery() {
    discoverJob = null
    discoveryDeadlineMillis = null
    discoveryPollsEnabled = false
    statusState.update { it.copy(isDiscovering = false, discoverySecondsRemaining = 0) }
}
