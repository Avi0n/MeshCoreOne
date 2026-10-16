package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.RemoteNodeError

/** Expands/collapses the status section, loading it once on the first expand. */
fun NodeStatusViewModel.setStatusExpanded(expanded: Boolean) {
    statusState.update { it.copy(statusExpanded = expanded) }
    val current = statusState.value
    if (expanded) loadOcvSettings()
    if (expanded && !current.statusLoaded && !current.isLoadingStatus) requestStatus()
}

/** Expands/collapses live telemetry, requesting on first expand (iOS `NodeTelemetryDisclosureSection`). */
fun NodeStatusViewModel.setTelemetryExpanded(expanded: Boolean) {
    statusState.update { it.copy(telemetryExpanded = expanded) }
    val current = statusState.value
    if (expanded) loadOcvSettings()
    if (expanded && !current.telemetryLoaded && !current.isLoadingTelemetry) requestTelemetry()
}

/** Expands/collapses the owner-info section, loading it once on the first expand. */
fun NodeStatusViewModel.setOwnerInfoExpanded(expanded: Boolean) {
    statusState.update { it.copy(ownerInfoExpanded = expanded) }
    val current = statusState.value
    if (expanded && !current.ownerInfoLoaded && !current.isLoadingOwnerInfo) requestOwnerInfo()
}

/** Expands/collapses the neighbours section, loading it once on the first expand. */
fun NodeStatusViewModel.setNeighborsExpanded(expanded: Boolean) {
    statusState.update { it.copy(neighborsExpanded = expanded) }
    if (expanded && statusState.value.isDiscovering) {
        discoveryPollsEnabled = true
    } else if (!expanded) {
        pauseDiscoveryPolls()
    }
    val current = statusState.value
    if (expanded && !current.neighborsLoaded && !current.isLoadingNeighbors) requestNeighbors()
}

/** Expands/collapses the battery-curve section, loading OCV settings once on first expand. */
fun NodeStatusViewModel.setBatteryCurveExpanded(expanded: Boolean) {
    statusState.update { it.copy(batteryCurveExpanded = expanded) }
    val current = statusState.value
    if (expanded && !current.ocvLoaded) loadOcvSettings()
}

/** Reloads the status counters (the section reload button). */
fun NodeStatusViewModel.requestStatus() {
    if (isClosed) return
    val service = dependencies.repeaterAdminService() ?: return
    statusRequest.launch(
        begin = { statusState.update { it.copy(isLoadingStatus = true, statusSectionError = null) } },
        onCancel = { statusState.update { it.copy(isLoadingStatus = false) } },
        onFailure = { failStatus(it.toStatusSectionError()) },
    ) {
        val isCurrent = { !isClosed && dependencies.repeaterAdminService() === service }
        val response =
            currentRemoteRequest(isCurrent) {
                retrying { timeout -> currentRemoteRequest(isCurrent) { service.requestStatus(session.id, timeout) } }
            }
        applyStatus(response, isCurrent)
    }
}

/**
 * Live `requestTelemetry` for the session: takes the bridge-decoded [TelemetryResponse.points], drops channel 0,
 * and records numeric snapshot entries (iOS `handleTelemetryResponse`).
 */
fun NodeStatusViewModel.requestTelemetry() {
    if (isClosed) return
    val service = dependencies.repeaterAdminService() ?: return
    telemetryRequest.launch(
        begin = { statusState.update { it.copy(isLoadingTelemetry = true, telemetrySectionError = null) } },
        onCancel = { statusState.update { it.copy(isLoadingTelemetry = false) } },
        onFailure = { error ->
            statusState.update {
                it.copy(isLoadingTelemetry = false, telemetrySectionError = error.toStatusSectionError())
            }
        },
    ) {
        val isCurrent = { !isClosed && dependencies.repeaterAdminService() === service }
        val response =
            currentRemoteRequest(isCurrent) {
                retrying { timeout ->
                    currentRemoteRequest(isCurrent) { service.requestTelemetry(session.id, timeout) }
                }
            }
        applyTelemetry(response, isCurrent)
    }
}

/** Reloads the owner info (the section reload button). */
fun NodeStatusViewModel.requestOwnerInfo() {
    if (isClosed) return
    val service = dependencies.repeaterAdminService() ?: return
    ownerInfoRequest.launch(
        begin = { statusState.update { it.copy(isLoadingOwnerInfo = true, ownerInfoSectionError = null) } },
        onCancel = { statusState.update { it.copy(isLoadingOwnerInfo = false) } },
        onFailure = { error ->
            statusState.update {
                it.copy(isLoadingOwnerInfo = false, ownerInfoSectionError = error.toStatusSectionError())
            }
        },
    ) {
        val isCurrent = { !isClosed && dependencies.repeaterAdminService() === service }
        val response = currentRemoteRequest(isCurrent) {
            retrying { timeout -> currentRemoteRequest(isCurrent) { service.requestOwnerInfo(session.id, timeout) } }
        }
        statusState.update {
            it.copy(
                ownerInfo = response.ownerInfo,
                firmwareVersion = response.firmwareVersion.ifEmpty { null },
                ownerInfoLoaded = true,
                isLoadingOwnerInfo = false,
            )
        }
    }
}

/** Reloads the neighbour table (the section reload button). Skipped while discovery is running. */
fun NodeStatusViewModel.requestNeighbors() {
    if (isClosed) return
    if (statusState.value.isDiscovering) return
    val service = dependencies.repeaterAdminService() ?: return
    neighborsRequest.launch(
        begin = { statusState.update { it.copy(isLoadingNeighbors = true, neighborsSectionError = null) } },
        onCancel = { statusState.update { it.copy(isLoadingNeighbors = false) } },
        onFailure = { error ->
            statusState.update {
                it.copy(isLoadingNeighbors = false, neighborsSectionError = error.toStatusSectionError())
            }
        },
    ) {
        val isCurrent = { !isClosed && dependencies.repeaterAdminService() === service }
        val response = currentRemoteRequest(isCurrent) {
            retrying { timeout ->
                currentRemoteRequest(isCurrent) { service.fetchAllNeighbors(session.id, timeout = timeout) }
            }
        }
        applyNeighbors(response.neighbours, isCurrent)
    }
}

/** A timeout stays the timeout error. Any other exception keeps its section message. A non-exception propagates. */
private fun Throwable.toStatusSectionError(): NodeSectionError = when (this) {
    is RemoteNodeError.Timeout -> NodeSectionError.Timeout
    is Exception -> toSectionError()
    else -> throw this
}
