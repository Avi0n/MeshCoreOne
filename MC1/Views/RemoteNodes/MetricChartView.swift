import Charts
import MC1Services
import SwiftUI

/// Reusable mini-chart for a single time-series metric.
struct MetricChartView: View {
    let title: String
    let unit: String
    let dataPoints: [DataPoint]
    let accentColor: Color
    var yAxisDomain: ClosedRange<Double>?

    @State private var selectedDate: Date?

    private var selectedPoint: DataPoint? {
        guard let selectedDate else { return nil }
        return dataPoints.min { abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate)) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            MetricChartHeader(title: title, unit: unit, selectedPoint: selectedPoint, accentColor: accentColor)

            if dataPoints.count < 2 {
                MetricChartEmptyState(value: dataPoints.first?.value, unit: unit)
            } else {
                MetricChartContent(title: title, dataPoints: dataPoints, accentColor: accentColor, yAxisDomain: yAxisDomain, selectedDate: $selectedDate, selectedPoint: selectedPoint)
            }
        }
    }

    struct DataPoint: Identifiable {
        let id: UUID
        let date: Date
        let value: Double
    }
}

/// Header row that shows the title, and selected value + timestamp when scrubbing.
private struct MetricChartHeader: View {
    let title: String
    let unit: String
    let selectedPoint: MetricChartView.DataPoint?
    let accentColor: Color

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.subheadline)
                .bold()

            Spacer()

            if let selectedPoint {
                Text("\(selectedPoint.value, format: .number) \(unit)")
                    .bold()
                    .foregroundStyle(accentColor)
                + Text("  ")
                + Text(selectedPoint.date, format: .dateTime.month(.abbreviated).day().hour().minute())
                    .foregroundStyle(.secondary)
            }
        }
        .font(.caption)
        .animation(.none, value: selectedPoint?.id)
    }
}

/// Chart content with line and point marks.
private struct MetricChartContent: View {
    let title: String
    let dataPoints: [MetricChartView.DataPoint]
    let accentColor: Color
    let yAxisDomain: ClosedRange<Double>?
    @Binding var selectedDate: Date?
    let selectedPoint: MetricChartView.DataPoint?

    var body: some View {
        chart
            .chartXSelection(value: $selectedDate)
            .chartGesture { proxy in
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        proxy.selectXValue(at: value.location.x)
                    }
                    .onEnded { _ in
                        selectedDate = nil
                    }
            }
            .chartYAxis {
                AxisMarks(position: .leading)
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                }
            }
            .accessibilityLabel(title)
            .frame(height: 180)
    }

    @ViewBuilder
    private var chart: some View {
        let base = Chart {
            ForEach(dataPoints) { point in
                LineMark(
                    x: .value("Time", point.date),
                    y: .value(title, point.value)
                )
                .interpolationMethod(.linear)
                .foregroundStyle(accentColor.opacity(0.5))

                PointMark(
                    x: .value("Time", point.date),
                    y: .value(title, point.value)
                )
                .foregroundStyle(accentColor)
                .symbolSize(30)
            }

            if let selectedPoint {
                RuleMark(x: .value("Selected", selectedPoint.date))
                    .foregroundStyle(.secondary.opacity(0.3))
                    .lineStyle(StrokeStyle(dash: [4, 4]))
                    .zIndex(-1)
            }
        }

        if let yAxisDomain {
            base.chartYScale(domain: yAxisDomain)
        } else {
            base
        }
    }
}

// MARK: - OCV Chart Domain

extension Array where Element == Int {
    /// Computes a chart Y-axis domain in volts from millivolt OCV values, with a ±buffer.
    /// Unions the OCV range with actual data points so outliers are never clipped.
    func voltageChartDomain(
        dataPoints: [MetricChartView.DataPoint] = [],
        bufferMV: Int = 500
    ) -> ClosedRange<Double>? {
        guard let ocvMin = self.min(), let ocvMax = self.max() else { return nil }
        var lo = Double(ocvMin) / 1000.0
        var hi = Double(ocvMax) / 1000.0
        let values = dataPoints.map(\.value)
        if let dataMin = values.min() { lo = Swift.min(lo, dataMin) }
        if let dataMax = values.max() { hi = Swift.max(hi, dataMax) }
        let buffer = Double(bufferMV) / 1000.0
        return Swift.max(0, lo - buffer) ... hi + buffer
    }
}

/// Empty state shown when fewer than 2 data points exist.
private struct MetricChartEmptyState: View {
    let value: Double?
    let unit: String

    var body: some View {
        VStack {
            if let value {
                Text("\(value.formatted()) \(unit)")
                    .font(.title2)
            }
            Text(L10n.RemoteNodes.RemoteNodes.History.checkBack)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 80)
    }
}
