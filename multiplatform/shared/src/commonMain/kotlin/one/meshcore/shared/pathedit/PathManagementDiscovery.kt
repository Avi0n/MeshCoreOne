package one.meshcore.shared.pathedit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.sendPathDiscovery
import one.meshcore.shared.meshcore.FirmwareSuggestedTimeout
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.runReportingException
import kotlin.math.min

// MARK: Discovery

/**
 * Initiates path discovery. The overall wait is floored at
 * [FirmwareSuggestedTimeout.PATH_DISCOVERY_MINIMUM_OVERALL_SECONDS]
 * and re-sends the request while waiting (flood `est_timeout` underestimates the multi-hop return leg). The
 * timeout is terminal for the UI: [PathDiscoveryResult.NoPathFound] is set once the budget elapses, and a late
 * push after that is ignored by [handleDiscoveryResponse].
 */
fun PathManagementViewModel.discoverPath(contact: ContactDto) {
    if (jobs.isClosed) return
    discoveryJob?.cancel()
    countdownJob?.cancel()
    discoveringContact = contact
    editState.update { it.copy(isDiscovering = true, discoveryResult = null, errorMessage = null) }
    discoveryJob = jobs.launch {
        val service = writeService() ?: run {
            discoveringContact = null
            editState.update { it.copy(isDiscovering = false) }
            return@launch
        }
        try {
            runReportingException(
                onFailure = { error ->
                    editState.update {
                        it.copy(
                            discoveryResult = PathDiscoveryResult.Failed(serviceMessage(error)),
                            showDiscoveryResult = true,
                        )
                    }
                },
            ) {
                val sent = service.sendPathDiscovery(contact.publicKey)
                val timeoutSeconds = FirmwareSuggestedTimeout.pathDiscoverySeconds(sent.suggestedTimeoutMs)
                startCountdown(timeoutSeconds)
                // `handleDiscoveryResponse` cancels this job on a matching push; a missing firmware hint
                // uses one send for the whole budget.
                waitForDiscoveryResponse(timeoutSeconds, sent.suggestedTimeoutMs, service, contact)
                // The wait ran to the end without a push: terminal no-path.
                editState.update {
                    it.copy(discoveryResult = PathDiscoveryResult.NoPathFound, showDiscoveryResult = true)
                }
            }
        } finally {
            countdownJob?.cancel()
            if (discoveringContact === contact) discoveringContact = null
            editState.update { it.copy(isDiscovering = false, discoverySecondsRemaining = null) }
        }
    }
}

/** iOS path-discovery retransmit warning, including the failure detail. */
internal fun pathDiscoveryResendFailedLine(attempt: Int, detail: String): String =
    "Path discovery retransmit #$attempt failed: $detail; continuing wait"

/**
 * Sleeps until the overall budget elapses, re-sending the discovery at [FirmwareSuggestedTimeout.retransmitIntervalMs]
 * spacing (companion holds one pending-discovery tag and replaces it on each send). A resend that fails is
 * logged and the wait continues; cancellation ends the wait early.
 */
private suspend fun PathManagementViewModel.waitForDiscoveryResponse(
    timeoutSeconds: Double,
    suggestedTimeoutMs: Long,
    service: ContactService,
    contact: ContactDto,
) {
    var remainingMs = (timeoutSeconds * PathManagementViewModel.MILLIS_PER_SECOND).toLong()
    val retransmitMs = FirmwareSuggestedTimeout.retransmitIntervalMs(suggestedTimeoutMs)
    var retransmitCount = 0
    while (remainingMs > 0) {
        val sleepMs = if (retransmitMs != null) min(remainingMs, retransmitMs) else remainingMs
        delay(sleepMs)
        remainingMs -= sleepMs
        if (remainingMs <= 0 || retransmitMs == null) return
        retransmitCount += 1
        runReportingException(
            onFailure = { error ->
                dependencies.warning(
                    pathDiscoveryResendFailedLine(retransmitCount, error.message ?: error.toString()),
                )
            },
        ) {
            service.sendPathDiscovery(contact.publicKey)
        }
    }
}

/**
 * Handles a discovery push matched to the in-flight contact. Ignores the push if the response's key prefix
 * doesn't match the contact discovery is running for (empty prefix never matches), so a push meant for a
 * different node can't resolve this one.
 */
fun PathManagementViewModel.handleDiscoveryResponse(response: PathInfo) {
    val contact = discoveringContact ?: return
    if (!response.matches(contact.publicKey)) return
    handleDiscoveryResponse(response.outHopCount())
}

/**
 * Handles a discovery push. Ignored once discovery has ended (timeout or cancel), so a late push can't flip a
 * terminal failure to success. A `null` [hopCount] means the wire's `out_path_len` used the reserved hash mode
 * and couldn't decode — treated as no valid path, not a silent direct route.
 */
fun PathManagementViewModel.handleDiscoveryResponse(hopCount: Int?) {
    if (!editState.value.isDiscovering) return
    discoveryJob?.cancel()
    countdownJob?.cancel()
    discoveringContact = null
    val result = if (hopCount != null) PathDiscoveryResult.Success(hopCount) else PathDiscoveryResult.NoPathFound
    editState.update {
        it.copy(
            isDiscovering = false,
            discoverySecondsRemaining = null,
            discoveryResult = result,
            showDiscoveryResult = true,
        )
    }
    dependencies.onContactNeedsRefresh()
}

/** Cancels an in-flight discovery. */
fun PathManagementViewModel.cancelDiscovery() {
    discoveryJob?.cancel()
    countdownJob?.cancel()
    discoveringContact = null
    editState.update { it.copy(isDiscovering = false, discoverySecondsRemaining = null) }
}

/** Retires this editor and rejects later radio or recent-hop work. */
internal fun PathManagementViewModel.close() {
    if (jobs.isClosed) return
    cancelDiscovery()
    jobs.close()
}

private fun PathManagementViewModel.startCountdown(timeoutSeconds: Double) {
    countdownJob?.cancel()
    editState.update { it.copy(discoverySecondsRemaining = timeoutSeconds.toInt()) }
    countdownJob = jobs.launch {
        var remainingMs = (timeoutSeconds * PathManagementViewModel.MILLIS_PER_SECOND).toLong()
        while (remainingMs > 0) {
            delay(PathManagementViewModel.COUNTDOWN_TICK_MS)
            remainingMs -= PathManagementViewModel.COUNTDOWN_TICK_MS
            editState.update {
                it.copy(
                    discoverySecondsRemaining = (remainingMs / PathManagementViewModel.MILLIS_PER_SECOND).toInt(),
                )
            }
        }
    }
}

// MARK: Dismissals

fun PathManagementViewModel.clearError() = editState.update { it.copy(errorMessage = null) }

fun PathManagementViewModel.dismissDiscoveryResult() = editState.update { it.copy(showDiscoveryResult = false) }
