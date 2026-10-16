package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Whether [generation] is still the live connect attempt. Nothing cancels an in-flight ceremony, so
// every resume point re-checks this rather than writing the rung on trust.
internal fun ConnectionManager.ownsConnectAttempt(generation: Long): Boolean = connectGeneration == generation

// A superseded attempt must not write `mutableState`, the WiFi endpoint, or anything else the replacement now
// owns, so the fence runs before the first such write and again at every retry.
internal fun ConnectionManager.throwIfSuperseded(generation: Long, lastError: Throwable? = null) {
    if (ownsConnectAttempt(generation)) return
    throw lastError ?: CancellationException("connect superseded")
}

/**
 * The caller's scope died mid-attempt (a connect sheet or activity going away). The half-built link is
 * still installed with no drop monitor, so it has to come down here or the manager parks on
 * Connecting until the next user-driven connect. A superseded attempt owns nothing and leaves the
 * replacement alone. Ready already has a drop monitor: the sheet dismisses on that rung while
 * connect() is still in the clock-sync tail, and tearing down would drop a just-synced radio.
 */
internal suspend fun ConnectionManager.abandonCancelledConnect(generation: Long) {
    if (!ownsConnectAttempt(generation)) return
    if (!shouldAbandonCancelledConnect(mutableState.value)) {
        logger.info(BLE_CONNECT_CANCEL_AFTER_READY)
        return
    }
    val retiredGeneration = withContext(NonCancellable) { tearDownCurrent(stopSession = false) }
    val resourcesCleared = services == null && session == null && link == null
    if (!ownsConnectAttempt(retiredGeneration) || !resourcesCleared) return
    mutableState.value = ConnectionState.Disconnected
}

// The same fence once the container is installed. It is [isSyncLoopValid] without the operational test,
// because the ceremony resumes while its own rung is still Connected — below the rung a loop runs at.
internal fun ConnectionManager.ownsConnectCeremony(generation: Long, container: ServiceContainer): Boolean =
    wantsConnection && ownsConnectAttempt(generation) && services === container

internal fun ConnectionManager.logPromotionSuppressed(generation: Long, container: ServiceContainer) {
    logger.warning("Promotion suppressed: ${promotionSuppressedReason(generation, container)}")
}

internal fun ConnectionManager.logPromotionSuppressed(container: ServiceContainer) {
    logger.warning("Promotion suppressed: ${promotionSuppressedReason(container)}")
}

internal fun ConnectionManager.promotionSuppressedReason(generation: Long, container: ServiceContainer): String = when {
    !wantsConnection -> "user disconnected"
    services !== container -> "services replaced or nil"
    !ownsConnectAttempt(generation) -> "caller guard failed (e.g. reconnect generation)"
    else -> "caller guard failed (e.g. reconnect generation)"
}

internal fun ConnectionManager.promotionSuppressedReason(container: ServiceContainer): String = when {
    !wantsConnection -> "user disconnected"
    services !== container -> "services replaced or nil"
    else -> "caller guard failed (e.g. reconnect generation)"
}

internal fun ConnectionManager.canResumeSyncNotifications(container: ServiceContainer): Boolean =
    services == null || services === container

internal fun ConnectionManager.cancelResyncLoop() {
    resyncJob?.cancel()
    resyncJob = null
}
