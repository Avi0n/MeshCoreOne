import MC1Services
import SwiftUI

/// The radio-metric history charts (battery, signal quality, packet and post counters)
/// shared by `NodeStatusHistoryView` and `TelemetryHistoryOverviewView`. Charts with no
/// data are skipped. Hosts supply `chartContainer` to wrap each chart in their own row
/// chrome (a themed `Section` in the drill-down list, a bare row inside the overview's
/// disclosure group).
struct RadioMetricCharts<ChartContainer: View>: View {
    let snapshots: [NodeStatusSnapshotDTO]
    let ocvArray: [Int]
    @ViewBuilder let chartContainer: (MetricChartView) -> ChartContainer

    var body: some View {
        let batteryPoints = snapshots.compactMap { s in
            s.batteryMillivolts.map {
                MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0) / 1000.0)
            }
        }
        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.battery, unit: "V", color: .mint,
            dataPoints: batteryPoints,
            yAxisDomain: ocvArray.voltageChartDomain(dataPoints: batteryPoints)
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.snr, unit: "dB", color: .blue,
            dataPoints: snapshots.compactMap { s in
                s.lastSNR.map { .init(id: s.id, date: s.timestamp, value: $0) }
            }
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.rssi, unit: "dBm", color: .purple,
            dataPoints: snapshots.compactMap { s in
                s.lastRSSI.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
            }
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.noiseFloor, unit: "dBm", color: .indigo,
            dataPoints: snapshots.compactMap { s in
                s.noiseFloor.map { .init(id: s.id, date: s.timestamp, value: Double($0)) }
            }
        )

        let packetsSentPoints = snapshots.compactMap { s in
            s.packetsSent.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
        }
        let packetsReceivedPoints = snapshots.compactMap { s in
            s.packetsReceived.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
        }
        let receiveErrorPoints = snapshots.compactMap { s in
            s.receiveErrors.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
        }
        let postsReceivedPoints = snapshots.compactMap { s in
            s.postedCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
        }
        let postsPushedPoints = snapshots.compactMap { s in
            s.postPushCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
        }
        let packetDomain = [MetricChartView.DataPoint].sharedDomain(for: [
            packetsSentPoints, packetsReceivedPoints, receiveErrorPoints
        ])

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.packetsSent, unit: "", color: .green,
            dataPoints: packetsSentPoints, yAxisDomain: packetDomain
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.packetsReceived, unit: "", color: .orange,
            dataPoints: packetsReceivedPoints, yAxisDomain: packetDomain
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.History.receiveErrors, unit: "", color: .red,
            dataPoints: receiveErrorPoints, yAxisDomain: packetDomain
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, unit: "", color: .purple,
            dataPoints: postsReceivedPoints
        )

        chart(
            title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, unit: "", color: .cyan,
            dataPoints: postsPushedPoints
        )
    }

    @ViewBuilder
    private func chart(
        title: String, unit: String, color: Color,
        dataPoints: [MetricChartView.DataPoint],
        yAxisDomain: ClosedRange<Double>? = nil
    ) -> some View {
        if !dataPoints.isEmpty {
            chartContainer(
                MetricChartView(
                    title: title, unit: unit,
                    dataPoints: dataPoints, accentColor: color,
                    yAxisDomain: yAxisDomain
                )
            )
        }
    }
}
