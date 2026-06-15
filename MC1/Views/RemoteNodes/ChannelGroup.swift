import MC1Services

struct ChannelGroup: Identifiable {
    let channel: Int
    let charts: [TelemetryChartGroup]
    var id: Int { channel }

    /// Builds channel groups from telemetry entries in the given snapshots,
    /// grouping by channel and sensor type, sorted by chart priority then alphabetically.
    static func groups(from snapshots: [NodeStatusSnapshotDTO]) -> [ChannelGroup] {
        let allEntries = snapshots.flatMap { snapshot in
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
                key: "\(channel)-\(type)", title: sensorType?.localizedName ?? type, sensorType: sensorType, dataPoints: []
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

struct TelemetryChartGroup: Identifiable {
    let key: String
    let title: String
    let sensorType: LPPSensorType?
    var dataPoints: [MetricChartView.DataPoint]
    var id: String { key }
}
