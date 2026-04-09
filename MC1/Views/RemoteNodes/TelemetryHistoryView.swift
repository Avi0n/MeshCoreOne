import Charts
import MC1Services
import SwiftUI

/// Drill-down view showing historical charts for telemetry metrics grouped by channel and type.
struct TelemetryHistoryView: View {
    let fetchSnapshots: @Sendable () async -> [NodeStatusSnapshotDTO]
    let ocvArray: [Int]

    @State private var snapshots: [NodeStatusSnapshotDTO] = []
    @State private var timeRange: HistoryTimeRange = .all

    private var filteredSnapshots: [NodeStatusSnapshotDTO] {
        guard let start = timeRange.startDate else { return snapshots }
        return snapshots.filter { $0.timestamp >= start }
    }

    var body: some View {
        List {
            HistoryTimeRangePicker(selection: $timeRange)

            let groups = channelGroups
            if groups.count > 1 {
                ForEach(groups) { channelGroup in
                    Section {
                        ForEach(channelGroup.charts, id: \.key) { chart in
                            chartView(for: chart)
                        }
                    } header: {
                        Text(L10n.RemoteNodes.RemoteNodes.Status.channel(channelGroup.channel))
                    }
                }
            } else if let singleGroup = groups.first {
                ForEach(singleGroup.charts, id: \.key) { chart in
                    Section {
                        chartView(for: chart)
                    }
                }
            }
        }
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
            yAxisDomain: chart.sensorType == .voltage ? ocvArray.voltageChartDomain() : nil
        )
    }

    private var channelGroups: [ChannelGroup] {
        let allEntries = filteredSnapshots.flatMap { snapshot in
            (snapshot.telemetryEntries ?? []).map { (snapshot: snapshot, entry: $0) }
        }

        guard !allEntries.isEmpty else { return [] }

        var channelTypeGroups: [Int: [String: TelemetryChartGroup]] = [:]

        for item in allEntries {
            let channel = item.entry.channel
            let type = item.entry.type
            let sensorType = LPPSensorType(name: type)
            let point = MetricChartView.DataPoint(
                id: item.snapshot.id,
                date: item.snapshot.timestamp,
                value: sensorType?.convertedValue(item.entry.value) ?? item.entry.value
            )

            channelTypeGroups[channel, default: [:]][type, default: TelemetryChartGroup(
                key: "\(channel)-\(type)", title: type, sensorType: sensorType, dataPoints: []
            )].dataPoints.append(point)
        }

        return channelTypeGroups.keys.sorted().map { channel in
            let charts = channelTypeGroups[channel]!.values.sorted { lhs, rhs in
                let lhsPriority = lhs.sensorType?.chartSortPriority ?? 1
                let rhsPriority = rhs.sensorType?.chartSortPriority ?? 1
                if lhsPriority != rhsPriority { return lhsPriority < rhsPriority }
                return lhs.title.localizedStandardCompare(rhs.title) == .orderedAscending
            }
            return ChannelGroup(channel: channel, charts: charts)
        }
    }
}
