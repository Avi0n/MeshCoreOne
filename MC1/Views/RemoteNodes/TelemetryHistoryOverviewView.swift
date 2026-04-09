import MC1Services
import SwiftUI

/// Offline-accessible overview of all historical telemetry charts for a repeater.
struct TelemetryHistoryOverviewView: View {
    let publicKey: Data
    let deviceID: UUID
    let showNeighbors: Bool

    @Environment(\.appState) private var appState
    @State private var viewModel = TelemetryHistoryOverviewViewModel()
    @State private var radioExpanded = true
    @State private var sensorsExpanded: Bool
    @State private var neighborsExpanded = false

    init(publicKey: Data, deviceID: UUID, showNeighbors: Bool = true) {
        self.publicKey = publicKey
        self.deviceID = deviceID
        self.showNeighbors = showNeighbors
        self._sensorsExpanded = State(initialValue: !showNeighbors)
    }

    var body: some View {
        List {
            if !viewModel.hasSnapshots {
                emptyState
            } else {
                HistoryTimeRangePicker(selection: $viewModel.timeRange)
                radioSection
                sensorsSection
                if showNeighbors {
                    neighborsSection
                }
                retentionFooter
            }
        }
        .navigationTitle(L10n.RemoteNodes.RemoteNodes.History.overviewTitle)
        .liquidGlassToolbarBackground()
        .task {
            guard let store = appState.offlineDataStore else { return }
            await viewModel.loadData(
                dataStore: store, publicKey: publicKey, deviceID: deviceID
            )
        }
    }

    // MARK: - Empty State

    @ViewBuilder
    private var emptyState: some View {
        ContentUnavailableView(
            L10n.RemoteNodes.RemoteNodes.History.overviewTitle,
            systemImage: "chart.line.uptrend.xyaxis",
            description: Text(L10n.RemoteNodes.RemoteNodes.History.noSnapshotsMessage)
        )
    }

    // MARK: - Radio Section

    @ViewBuilder
    private var radioSection: some View {
        let filtered = viewModel.filteredSnapshots
        let hasRadioData = filtered.contains {
            $0.batteryMillivolts != nil || $0.lastSNR != nil ||
            $0.lastRSSI != nil || $0.noiseFloor != nil ||
            $0.packetsSent != nil || $0.packetsReceived != nil ||
            $0.receiveErrors != nil ||
            $0.postedCount != nil || $0.postPushCount != nil
        }

        if hasRadioData {
            Section {
                DisclosureGroup(
                    L10n.RemoteNodes.RemoteNodes.History.radioSection,
                    isExpanded: $radioExpanded
                ) {
                    let batteryPoints = filtered.compactMap { s in
                        s.batteryMillivolts.map {
                            MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0) / 1000.0)
                        }
                    }
                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.battery,
                        unit: "V", color: .mint,
                        dataPoints: batteryPoints,
                        yAxisDomain: viewModel.ocvArray.voltageChartDomain(dataPoints: batteryPoints)
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.snr,
                        unit: "dB", color: .blue,
                        dataPoints: filtered.compactMap { s in
                            s.lastSNR.map { .init(id: s.id, date: s.timestamp, value: $0) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.rssi,
                        unit: "dBm", color: .purple,
                        dataPoints: filtered.compactMap { s in
                            s.lastRSSI.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.noiseFloor,
                        unit: "dBm", color: .indigo,
                        dataPoints: filtered.compactMap { s in
                            s.noiseFloor.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.packetsSent,
                        unit: "", color: .green,
                        dataPoints: filtered.compactMap { s in
                            s.packetsSent.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.packetsReceived,
                        unit: "", color: .orange,
                        dataPoints: filtered.compactMap { s in
                            s.packetsReceived.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.History.receiveErrors,
                        unit: "", color: .red,
                        dataPoints: filtered.compactMap { s in
                            s.receiveErrors.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived,
                        unit: "", color: .purple,
                        dataPoints: filtered.compactMap { s in
                            s.postedCount.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )

                    metricChart(
                        title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed,
                        unit: "", color: .cyan,
                        dataPoints: filtered.compactMap { s in
                            s.postPushCount.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                        }
                    )
                }
            }
        }
    }

    // MARK: - Sensors Section

    @ViewBuilder
    private var sensorsSection: some View {
        if viewModel.hasTelemetryData {
            let groups = viewModel.channelGroups
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
        } else if viewModel.hasSnapshots {
            Section {
                Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
                    L10n.RemoteNodes.RemoteNodes.History.sensorsSection
                ))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Neighbors Section

    @ViewBuilder
    private var neighborsSection: some View {
        if viewModel.hasNeighborData {
            let neighborCharts = buildNeighborCharts()
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
        } else if viewModel.hasSnapshots {
            Section {
                Text(L10n.RemoteNodes.RemoteNodes.History.sectionNotCaptured(
                    L10n.RemoteNodes.RemoteNodes.History.neighborsSection
                ))
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Helpers

    @ViewBuilder
    private func metricChart(
        title: String, unit: String, color: Color,
        dataPoints: [MetricChartView.DataPoint],
        yAxisDomain: ClosedRange<Double>? = nil
    ) -> some View {
        if !dataPoints.isEmpty {
            MetricChartView(
                title: title, unit: unit,
                dataPoints: dataPoints, accentColor: color,
                yAxisDomain: yAxisDomain
            )
        }
    }

    private func chartView(for chart: TelemetryChartGroup) -> MetricChartView {
        MetricChartView(
            title: chart.title,
            unit: chart.sensorType?.localizedUnitSymbol ?? "",
            dataPoints: chart.dataPoints,
            accentColor: chart.sensorType?.chartColor ?? .cyan,
            yAxisDomain: chart.sensorType == .voltage ? viewModel.ocvArray.voltageChartDomain(dataPoints: chart.dataPoints) : nil
        )
    }

    private func buildNeighborCharts() -> [NeighborChart] {
        var charts: [Data: NeighborChart] = [:]
        for snapshot in viewModel.filteredSnapshots {
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
        Section {
        } footer: {
            Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
        }
    }
}

// MARK: - Private Types

private struct NeighborChart {
    let prefix: Data
    let name: String
    var dataPoints: [MetricChartView.DataPoint]
}
