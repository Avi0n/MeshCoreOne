package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException

/**
 * Discover ingest after a parsed advert. Cancellation is rethrown. Every other exception is
 * reported and the event is ignored. A thrown error still propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runDiscoveredIngest(
    onFailure: (Exception) -> Unit,
    block: suspend () -> DiscoveredNodeIngestResult,
): DiscoveredNodeIngestResult = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
    DiscoveredNodeIngestResult.Ignored
}
