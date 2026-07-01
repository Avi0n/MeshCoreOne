import MC1Services
import SwiftUI

/// Drill-down view showing historical charts for telemetry metrics grouped by channel and type.
struct TelemetryHistoryView: View {
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

      let groups = ChannelGroup.groups(from: filteredSnapshots)
      if groups.count > 1 {
        ForEach(groups) { channelGroup in
          Section {
            ForEach(channelGroup.charts) { chart in
              chartView(for: chart)
            }
          } header: {
            Text(L10n.RemoteNodes.RemoteNodes.Status.channel(channelGroup.channel))
          }
          .themedRowBackground(theme)
        }
      } else if let singleGroup = groups.first {
        ForEach(singleGroup.charts) { chart in
          Section {
            chartView(for: chart)
          }
          .themedRowBackground(theme)
        }
      }
    }
    .themedCanvas(theme)
    .chartScrubbingScrollLock()
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.telemetry)
    .liquidGlassToolbarBackground()
    .task {
      snapshots = await fetchSnapshots()
    }
  }

  private func chartView(for chart: TelemetryChartGroup) -> MetricChartView {
    MetricChartView(
      title: chart.title,
      unit: chart.sensorType?.localizedUnitSymbol ?? "",
      dataPoints: chart.dataPoints,
      accentColor: chart.sensorType?.chartColor ?? .cyan,
      yAxisDomain: chart.sensorType == .voltage ? ocvArray.voltageChartDomain(dataPoints: chart.dataPoints) : nil
    )
  }
}
