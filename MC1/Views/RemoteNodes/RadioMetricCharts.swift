import MC1Services
import SwiftUI

/// The radio-metric history charts (battery, signal quality, packet and post counters)
/// shared by `NodeStatusHistoryView` and `TelemetryHistoryOverviewView`. Charts with no
/// data are skipped. Hosts supply `chartContainer` to wrap each chart in their own row
/// chrome (a themed `Section` in the drill-down list, a bare row inside the overview's
/// disclosure group).
struct RadioMetricCharts<ChartContainer: View, PacketSection: View>: View {
  let snapshots: [NodeStatusSnapshotDTO]
  let ocvArray: [Int]
  @ViewBuilder let chartContainer: (MetricChartView) -> ChartContainer
  @ViewBuilder let packetSection: (PacketChartsGroup) -> PacketSection

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

    let sentDirectPoints = packets(for: \.sentDirect)
    let sentFloodPoints = packets(for: \.sentFlood)
    let receivedDirectPoints = packets(for: \.receivedDirect)
    let receivedFloodPoints = packets(for: \.receivedFlood)
    let directDuplicatePoints = packets(for: \.directDuplicates)
    let floodDuplicatePoints = packets(for: \.floodDuplicates)
    let receiveErrorPoints = packets(for: \.receiveErrors)
    let postsReceivedPoints = snapshots.compactMap { s in
      s.postedCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
    }
    let postsPushedPoints = snapshots.compactMap { s in
      s.postPushCount.map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
    }

    // Every packet chart shares one Y-axis domain spanning all series, so a user can see at
    // a glance which counter is climbing fastest.
    let packetDomain = [MetricChartView.DataPoint].sharedDomain(for: [
      sentDirectPoints, sentFloodPoints,
      receivedDirectPoints, receivedFloodPoints,
      directDuplicatePoints, floodDuplicatePoints,
      receiveErrorPoints,
    ])
    let packetCharts: [MetricChartView] = [
      overlaySeriesChart(
        title: L10n.RemoteNodes.RemoteNodes.History.packetsSent,
        direct: sentDirectPoints, flood: sentFloodPoints, yAxisDomain: packetDomain
      ),
      overlaySeriesChart(
        title: L10n.RemoteNodes.RemoteNodes.History.packetsReceived,
        direct: receivedDirectPoints, flood: receivedFloodPoints, yAxisDomain: packetDomain
      ),
      overlaySeriesChart(
        title: L10n.RemoteNodes.RemoteNodes.History.duplicates,
        direct: directDuplicatePoints, flood: floodDuplicatePoints, yAxisDomain: packetDomain
      ),
      receiveErrorPoints.isEmpty ? nil : MetricChartView(
        title: L10n.RemoteNodes.RemoteNodes.History.receiveErrors, unit: "",
        dataPoints: receiveErrorPoints, accentColor: .red,
        yAxisDomain: packetDomain
      ),
    ].compactMap(\.self)

    if !packetCharts.isEmpty {
      packetSection(PacketChartsGroup(charts: packetCharts))
    }

    chart(
      title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, unit: "", color: .purple,
      dataPoints: postsReceivedPoints
    )

    chart(
      title: L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, unit: "", color: .cyan,
      dataPoints: postsPushedPoints
    )
  }

  /// Builds the point array for a cumulative `UInt32?` packet counter across snapshots.
  private func packets(for keyPath: KeyPath<NodeStatusSnapshotDTO, UInt32?>) -> [MetricChartView.DataPoint] {
    snapshots.compactMap { s in
      s[keyPath: keyPath].map { MetricChartView.DataPoint(id: s.id, date: s.timestamp, value: Double($0)) }
    }
  }

  /// The standard Direct/Flood pair for an overlaid packet chart.
  private func directFloodSeries(
    direct: [MetricChartView.DataPoint],
    flood: [MetricChartView.DataPoint]
  ) -> [MetricChartView.Series] {
    [
      .init(name: L10n.RemoteNodes.RemoteNodes.History.direct, color: .blue, dataPoints: direct),
      .init(name: L10n.RemoteNodes.RemoteNodes.History.flood, color: .orange, dataPoints: flood),
    ]
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

  /// An overlaid Direct/Flood packet chart, or nil when neither series carries data.
  /// Empty series are dropped. The caller passes the domain shared across every packet
  /// chart so they all read on one scale.
  private func overlaySeriesChart(
    title: String,
    direct: [MetricChartView.DataPoint],
    flood: [MetricChartView.DataPoint],
    yAxisDomain: ClosedRange<Double>?
  ) -> MetricChartView? {
    let series = directFloodSeries(direct: direct, flood: flood).filter { !$0.dataPoints.isEmpty }
    guard !series.isEmpty else { return nil }
    return MetricChartView(
      title: title, unit: "", series: series,
      yAxisDomain: yAxisDomain
    )
  }
}

/// The packet-count charts stacked under a host-supplied `Packets` section header.
struct PacketChartsGroup: View {
  let charts: [MetricChartView]

  var body: some View {
    ForEach(Array(charts.enumerated()), id: \.offset) { _, chart in
      chart
    }
  }
}
