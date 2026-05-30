import Charts
import MC1Services
import SwiftUI

/// Drill-down view showing historical charts for status metrics (battery, SNR, RSSI, noise floor).
struct NodeStatusHistoryView: View {
    @Environment(\.appTheme) private var theme
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

            let packetsSentPoints = filtered.compactMap { s in
                s.packetsSent.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
            }
            let packetsReceivedPoints = filtered.compactMap { s in
                s.packetsReceived.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
            }
            let receiveErrorPoints = filtered.compactMap { s in
                s.receiveErrors.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
            }
            let postsReceivedPoints = filtered.compactMap { s in
                s.postedCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
            }
            let postsPushedPoints = filtered.compactMap { s in
                s.postPushCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
            }
            let packetDomain = [MetricChartView.DataPoint].sharedDomain(for: [
                packetsSentPoints, packetsReceivedPoints, receiveErrorPoints,
                postsReceivedPoints, postsPushedPoints
            ])

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.packetsSent, unit: "", color: .green,
                dataPoints: packetsSentPoints, yAxisDomain: packetDomain
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.packetsReceived, unit: "", color: .orange,
                dataPoints: packetsReceivedPoints, yAxisDomain: packetDomain
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.History.receiveErrors, unit: "", color: .red,
                dataPoints: receiveErrorPoints, yAxisDomain: packetDomain
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, unit: "", color: .purple,
                dataPoints: postsReceivedPoints, yAxisDomain: packetDomain
            )

            metricSection(
                title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, unit: "", color: .mint,
                dataPoints: postsPushedPoints, yAxisDomain: packetDomain
            )

            Section {
            } footer: {
                Text(L10n.RemoteNodes.RemoteNodes.History.retentionNotice)
            }
            .themedRowBackground(theme)
        }
        .themedCanvas(theme)
        .chartScrubbingScrollLock()
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
            .themedRowBackground(theme)
        }
    }
}
