import MC1Services
import SwiftUI

/// Offline-accessible overview of all historical telemetry charts for a repeater.
struct TelemetryHistoryOverviewView: View {
  let publicKey: Data
  let radioID: UUID
  let showNeighbors: Bool

  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var viewModel = TelemetryHistoryOverviewViewModel()
  @State private var radioExpanded = true
  @State private var sensorsExpanded: Bool
  @State private var neighborsExpanded = false

  init(publicKey: Data, radioID: UUID, showNeighbors: Bool = true) {
    self.publicKey = publicKey
    self.radioID = radioID
    self.showNeighbors = showNeighbors
    _sensorsExpanded = State(initialValue: !showNeighbors)
  }

  var body: some View {
    let filtered = viewModel.filteredSnapshots
    List {
      if !viewModel.hasSnapshots {
        emptyState
      } else {
        HistoryTimeRangePicker(selection: $viewModel.timeRange)
        radioSection(filtered: filtered)
        sensorsSection(filtered: filtered)
        if showNeighbors {
          neighborsSection(filtered: filtered)
        }
        retentionFooter
      }
    }
    .themedCanvas(theme)
    .chartScrubbingScrollLock()
    .navigationTitle(L10n.RemoteNodes.RemoteNodes.History.overviewTitle)
    .liquidGlassToolbarBackground()
    .task {
      guard let store = appState.offlineDataStore else { return }
      await viewModel.loadData(
        dataStore: store, publicKey: publicKey, radioID: radioID
      )
    }
  }

  // MARK: - Empty State

  private var emptyState: some View {
    ContentUnavailableView(
      L10n.RemoteNodes.RemoteNodes.History.overviewTitle,
      systemImage: "chart.line.uptrend.xyaxis",
      description: Text(L10n.RemoteNodes.RemoteNodes.History.noSnapshotsMessage)
    )
  }

  // MARK: - Radio Section

  @ViewBuilder
  private func radioSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    let hasRadioData = viewModel.hasRadioData(in: filtered)

    if hasRadioData {
      Section {
        DisclosureGroup(
          L10n.RemoteNodes.RemoteNodes.History.radioSection,
          isExpanded: $radioExpanded
        ) {
          RadioMetricCharts(snapshots: filtered, ocvArray: viewModel.ocvArray) { chart in
            chart
          } packetSection: { group in
            Section(L10n.RemoteNodes.RemoteNodes.History.packets) {
              group
            }
          }
        }
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Sensors Section

  @ViewBuilder
  private func sensorsSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    if viewModel.hasTelemetryData(in: filtered) {
      let groups = ChannelGroup.groups(from: filtered)
      Section {
        DisclosureGroup(
          L10n.RemoteNodes.RemoteNodes.History.sensorsSection,
          isExpanded: $sensorsExpanded
        ) {
          if groups.count > 1 {
            ForEach(groups) { group in
              Section(L10n.RemoteNodes.RemoteNodes.Status.channel(group.channel)) {
                ForEach(group.charts) { chart in
                  chartView(for: chart)
                }
              }
            }
          } else if let group = groups.first {
            ForEach(group.charts) { chart in
              chartView(for: chart)
            }
          }
        }
      }
      .themedRowBackground(theme)
    } else if viewModel.hasSnapshots {
      Section {
        Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
          L10n.RemoteNodes.RemoteNodes.History.sensorsSection
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Neighbors Section

  @ViewBuilder
  private func neighborsSection(filtered: [NodeStatusSnapshotDTO]) -> some View {
    if viewModel.hasNeighborData(in: filtered) {
      let neighborCharts = buildNeighborCharts(from: filtered)
      Section {
        DisclosureGroup(
          L10n.RemoteNodes.RemoteNodes.History.neighborsSection,
          isExpanded: $neighborsExpanded
        ) {
          ForEach(neighborCharts, id: \.prefix) { neighbor in
            MetricChartView(
              title: neighbor.name,
              unit: "dB",
              dataPoints: neighbor.dataPoints,
              accentColor: .blue
            )
          }
        }
      }
      .themedRowBackground(theme)
    } else if viewModel.hasSnapshots {
      Section {
        Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
          L10n.RemoteNodes.RemoteNodes.History.neighborsSection
        ))
        .font(.subheadline)
        .foregroundStyle(.secondary)
      }
      .themedRowBackground(theme)
    }
  }

  // MARK: - Helpers

  private func chartView(for chart: TelemetryChartGroup) -> MetricChartView {
    MetricChartView(
      title: chart.title,
      unit: chart.sensorType?.localizedUnitSymbol ?? "",
      dataPoints: chart.dataPoints,
      accentColor: chart.sensorType?.chartColor ?? .cyan,
      yAxisDomain: chart.sensorType == .voltage ? viewModel.ocvArray.voltageChartDomain(dataPoints: chart.dataPoints) : nil
    )
  }

  private func buildNeighborCharts(from filtered: [NodeStatusSnapshotDTO]) -> [NeighborChart] {
    var charts: [Data: NeighborChart] = [:]
    for snapshot in filtered {
      for neighbor in snapshot.neighborSnapshots ?? [] {
        let point = MetricChartView.DataPoint(
          id: snapshot.id, date: snapshot.timestamp, value: neighbor.snr
        )
        if charts[neighbor.publicKeyPrefix] != nil {
          charts[neighbor.publicKeyPrefix]!.dataPoints.append(point)
        } else {
          let hexName = neighbor.publicKeyPrefix
            .map { String(format: "%02X", $0) }.joined()
          let resolvedName = viewModel.resolveNeighborName(prefix: neighbor.publicKeyPrefix) ?? hexName
          charts[neighbor.publicKeyPrefix] = NeighborChart(
            prefix: neighbor.publicKeyPrefix,
            name: resolvedName,
            dataPoints: [point]
          )
        }
      }
    }
    return charts.values.sorted { $0.name < $1.name }
  }

  private var retentionFooter: some View {
    Section {} footer: {
      Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
    }
    .themedRowBackground(theme)
  }
}

// MARK: - Private Types

private struct NeighborChart {
  let prefix: Data
  let name: String
  var dataPoints: [MetricChartView.DataPoint]
}
