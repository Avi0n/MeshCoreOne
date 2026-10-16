package one.meshcore.shared.channel

/** iOS `ChannelService.syncChannelsPipelined` reconcile breaker dump. */
fun reconcileCircuitBreakerOpenLine(consecutiveTimeouts: Int): String =
    "Reconcile circuit breaker open: $consecutiveTimeouts consecutive timeouts, stopping reconcile"

/** iOS `ChannelService.syncChannelsPipelined` per-slot reconcile fail dump. */
fun reconcileFailedForChannelLine(index: Int, description: String): String =
    "Reconcile failed for channel $index: $description"
