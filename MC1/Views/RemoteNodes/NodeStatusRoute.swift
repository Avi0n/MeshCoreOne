import Foundation
import MC1Services
import SwiftUI

/// History drill-downs pushed from the shared node status sections. Value-based so each
/// push rebuilds the destination instead of reusing stale `@State` from a prior visit.
enum NodeStatusRoute: Hashable {
  case statusHistory
  case telemetryHistory
  case neighborChart(name: String, neighborPrefix: Data)
  case locationMap(fix: NodeLocationFix, name: String?)
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
      case let .neighborChart(name, neighborPrefix):
        NeighborSNRChartView(
          name: name,
          neighborPrefix: neighborPrefix,
          fetchSnapshots: helper.fetchHistory
        )
      case let .locationMap(fix, name):
        NodeLocationMapView(
          points: [MapPoint(
            id: UUID(),
            coordinate: fix.coordinate,
            pinStyle: .locationFixLatest,
            label: name,
            isClusterable: false,
            hopIndex: nil,
            badgeText: nil
          )],
          lines: [],
          reports: [:],
          title: L10n.RemoteNodes.RemoteNodes.Status.locationMapTitle,
          initialSelectionID: nil
        )
      }
    }
  }
}
