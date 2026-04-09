import Charts
import MC1Services
import SwiftUI

/// Drill-down view showing historical charts for status metrics (battery, SNR, RSSI, noise floor).
struct NodeStatusHistoryView: View {
    let fetchSnapshots: @Sendable () async -> [NodeStatusSnapshotDTO]
    let ocvArray: [Int]

    @State private var snapshots: [NodeStatusSnapshotDTO] = []
    @State private var timeRange: HistoryTimeRange = .all

    private var filteredSnapshots: [NodeStatusSnapshotDTO] {
        guard let start = timeRange.startDate else { return snapshots }
        return snapshots.filter { $0.timestamp >= start }
    }

    var body: some View {
        let filtered = filteredSnapshots
        List {
            HistoryTimeRangePicker(selection: $timeRange)

            let batteryPoints = filtered.compactMap { s in
                s.batteryMillivolts.map {
                    MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0) / 1000.0)
                }
            }
            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.battery, unit: "V", color: .mint,
                dataPoints: batteryPoints,
                yAxisDomain: ocvArray.voltageChartDomain(dataPoints: batteryPoints)
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.snr, unit: "dB", color: .blue,
                dataPoints: filtered.compactMap { s in
                    s.lastSNR.map { .init(id: s.id, date: s.timestamp, value: $0) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.rssi, unit: "dBm", color: .purple,
                dataPoints: filtered.compactMap { s in
                    s.lastRSSI.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.noiseFloor, unit: "dBm", color: .indigo,
                dataPoints: filtered.compactMap { s in
                    s.noiseFloor.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.packetsSent, unit: "", color: .green,
                dataPoints: filtered.compactMap { s in
                    s.packetsSent.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.packetsReceived, unit: "", color: .orange,
                dataPoints: filtered.compactMap { s in
                    s.packetsReceived.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.receiveErrors, unit: "", color: .red,
                dataPoints: filtered.compactMap { s in
                    s.receiveErrors.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, unit: "", color: .purple,
                dataPoints: filtered.compactMap { s in
                    s.postedCount.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, unit: "", color: .mint,
                dataPoints: filtered.compactMap { s in
                    s.postPushCount.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
                }
            )

            Section {
            } footer: {
                Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
            }
        }
        .navigationTitle(L10n.RemoteNodes.RemoteNodes.History.title)
        .liquidGlassToolbarBackground()
        .task {
            snapshots = await fetchSnapshots()
        }
    }

    @ViewBuilder
    private func metricSection(
        title: String, unit: String, color: Color,
        dataPoints: [MetricChartView.DataPoint],
        yAxisDomain: ClosedRange<Double>? = nil
    ) -> some View {
        if !dataPoints.isEmpty {
            Section {
                MetricChartView(title: title, unit: unit, dataPoints: dataPoints, accentColor: color, yAxisDomain: yAxisDomain)
            }
        }
    }
}
