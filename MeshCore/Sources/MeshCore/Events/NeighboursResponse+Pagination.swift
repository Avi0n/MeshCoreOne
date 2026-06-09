import Foundation

public extension NeighboursResponse {
    /// Absolute backstop on pagination round-trips. The empty-page guard already guarantees
    /// termination for well-behaved firmware; this bounds a pathological node that keeps
    /// reporting fresh rows without ever reaching its advertised total.
    static let maxPaginationPages = 512

    /// Aggregates every neighbour page from a node into a single response.
    ///
    /// A node returns at most one radio frame of neighbours per request (roughly a dozen
    /// entries), so the complete table is retrieved by repeating the request at advancing
    /// offsets until the accumulated count reaches the node-reported total.
    ///
    /// Termination is guaranteed two ways: pagination stops as soon as a page returns no
    /// rows (a stalled or out-of-range response cannot advance the offset), and by an
    /// absolute page cap. The returned response carries the node's reported `totalCount`,
    /// so a caller can compare it against `neighbours.count` to detect a truncated table.
    ///
    /// - Parameter fetchPage: Requests one page of neighbours starting at the given offset.
    /// - Returns: A response whose `neighbours` concatenates every page in request order.
    static func collectingAllPages(
        fetchPage: @Sendable (_ offset: UInt16) async throws -> NeighboursResponse
    ) async rethrows -> NeighboursResponse {
        var aggregated: [Neighbour] = []
        var firstPage: NeighboursResponse?
        var totalCount = 0

        for _ in 0..<maxPaginationPages {
            let offset = UInt16(min(aggregated.count, Int(UInt16.max)))
            let page = try await fetchPage(offset)
            if firstPage == nil { firstPage = page }
            totalCount = page.totalCount

            if page.neighbours.isEmpty { break }
            aggregated.append(contentsOf: page.neighbours)

            if aggregated.count >= totalCount { break }
        }

        return NeighboursResponse(
            publicKeyPrefix: firstPage?.publicKeyPrefix ?? Data(),
            tag: firstPage?.tag ?? Data(),
            totalCount: totalCount,
            neighbours: aggregated
        )
    }
}
