import Foundation
import SwiftUI

/// History drill-downs pushed from the shared node status sections. Value-based so each
/// push rebuilds the destination instead of reusing stale `@State` from a prior visit.
enum NodeStatusRoute: Hashable {
    case statusHistory
    case telemetryHistory
    case neighborChart(name: String, neighborPrefix: Data)
}

extension View {
    /// Registers the `NodeStatusRoute` destinations on the enclosing navigation stack,
    /// resolving each route against the host's `NodeStatusViewModel`. Apply to the `List`
    /// hosting the shared status sections, not to rows inside it.
    @MainActor
    func nodeStatusDestinations(helper: NodeStatusViewModel) -> some View {
        navigationDestination(for: NodeStatusRoute.self) { route in
            switch route {
            case .statusHistory:
                NodeStatusHistoryView(fetchSnapshots: helper.fetchHistory, ocvArray: helper.ocvValues)
            case .telemetryHistory:
                TelemetryHistoryView(fetchSnapshots: helper.fetchHistory, ocvArray: helper.ocvValues)
            case .neighborChart(let name, let neighborPrefix):
                NeighborSNRChartView(
                    name: name,
                    neighborPrefix: neighborPrefix,
                    fetchSnapshots: helper.fetchHistory
                )
            }
        }
    }
}
