import MC1Services
import SwiftUI

/// Drill-down view showing historical charts for status metrics (battery, SNR, RSSI, noise floor).
struct NodeStatusHistoryView: View {
  @Environment(\.appTheme) private var theme
  let fetchSnapshots: @Sendable () async -> [NodeStatusSnapshotDTO]
  let ocvArray: [Int]

  @State private var snapshots: [NodeStatusSnapshotDTO] = []
  @State private var timeRange: HistoryTimeRange = .default

  private var filteredSnapshots: [NodeStatusSnapshotDTO] {
    guard let start = timeRange.startDate else { return snapshots }
    return snapshots.filter { $0.timestamp >= start }
  }

  var body: some View {
    List {
      HistoryTimeRangePicker(selection: $timeRange)

      RadioMetricCharts(snapshots: filteredSnapshots, ocvArray: ocvArray) { chart in
        Section {
          chart
        }
        .themedRowBackground(theme)
      } packetSection: { group in
        Section(L10n.RemoteNodes.RemoteNodes.History.packets) {
          group
        }
        .themedRowBackground(theme)
      }

      Section {} footer: {
        Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
      }
      .themedRowBackground(theme)
    }
    .listSectionSpacing(.compact)
    .themedCanvas(theme)
    .chartScrubbingScrollLock()
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.History.title)
    .liquidGlassToolbarBackground()
    .task {
      snapshots = await fetchSnapshots()
    }
  }
}
