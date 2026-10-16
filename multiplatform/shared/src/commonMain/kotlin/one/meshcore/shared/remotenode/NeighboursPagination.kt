package one.meshcore.shared.remotenode

import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.meshcore.NeighboursResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Neighbour-page aggregation, mirroring the Swift `NeighboursResponse.collectingAllPages`. A node returns
 * at most one radio frame of neighbours per request, so the full table is retrieved by repeating the
 * request at advancing offsets until the accumulated count reaches the node-reported total. Termination is
 * guaranteed two ways: pagination stops as soon as a page returns no rows (a stalled response cannot
 * advance the offset), and by [MAX_PAGINATION_PAGES]. The returned response carries the node's reported
 * `totalCount`, so a caller can compare it against `neighbours.size` to detect a truncated table. This
 * aggregator is clock-free; the caller applies [INTER_PAGE_DELAY] between pages.
 */
object NeighboursPagination {
    /** Absolute backstop on pagination round-trips for a pathological node. */
    const val MAX_PAGINATION_PAGES: Int = 512

    /** Pause the caller applies before each page after the first, while the radio settles. */
    val INTER_PAGE_DELAY: Duration = 1.seconds

    /** The maximum offset the wire's `UInt16` can carry. */
    private const val MAX_OFFSET: Int = 65_535

    suspend fun collectingAllPages(fetchPage: suspend (offset: Int) -> NeighboursResponse): NeighboursResponse {
        val aggregated = mutableListOf<Neighbour>()
        var firstPage: NeighboursResponse? = null
        var totalCount = 0
        var reachedEnd = false
        var pageCount = 0

        while (!reachedEnd && pageCount < MAX_PAGINATION_PAGES) {
            val offset = minOf(aggregated.size, MAX_OFFSET)
            val response = fetchPage(offset)
            if (firstPage == null) firstPage = response
            totalCount = response.totalCount
            when {
                response.neighbours.isEmpty() -> reachedEnd = true
                else -> {
                    aggregated += response.neighbours
                    if (aggregated.size >= totalCount) reachedEnd = true
                }
            }
            pageCount++
        }

        return NeighboursResponse(
            publicKeyPrefix = firstPage?.publicKeyPrefix ?: ByteArray(0),
            tag = firstPage?.tag ?: ByteArray(0),
            totalCount = totalCount,
            neighbours = aggregated,
        )
    }
}
