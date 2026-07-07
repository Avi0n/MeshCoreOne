import Charts
import MC1Services
import SwiftUI

/// Reusable mini-chart for one or more time-series metrics.
///
/// Single-series charts render exactly as a one-line chart (no legend). Passing
/// more than one series switches to an overlaid multi-series layout with a
/// categorical foreground scale and legend.
struct MetricChartView: View {
  let title: String
  let unit: String
  let series: [Series]
  var yAxisDomain: ClosedRange<Double>?

  @State private var selectedDate: Date?

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      MetricChartHeader(
        title: title, unit: unit, series: series,
        selections: selections, scrubDate: scrubDate
      )

      if !hasEnoughData {
        MetricChartEmptyState(value: drawnSeries.first?.dataPoints.first?.value, unit: unit)
      } else {
        MetricChartContent(
          title: title, series: drawnSeries, yAxisDomain: yAxisDomain,
          selectedDate: $selectedDate, ruleDate: scrubDate, isMultiSeries: isMultiSeries
        )
      }
    }
  }

  private var isMultiSeries: Bool {
    series.count > 1
  }

  private var hasEnoughData: Bool {
    series.contains { $0.dataPoints.count >= 2 }
  }

  /// Series that actually carry points. Empty series draw nothing, so dropping
  /// them keeps the categorical scale and legend free of phantom entries.
  private var drawnSeries: [Series] {
    series.filter { !$0.dataPoints.isEmpty }
  }

  /// The scrub position snapped to the nearest plotted point's date, so the readout
  /// and rule line land on real samples rather than arbitrary times between them.
  private var scrubDate: Date? {
    guard let selectedDate else { return nil }
    return series.flatMap { $0.dataPoints.map(\.date) }.min(by: {
      abs($0.timeIntervalSince(selectedDate)) < abs($1.timeIntervalSince(selectedDate))
    })
  }

  /// The nearest point in each series to the snapped scrub date, or empty when not
  /// scrubbing. The header shows one value per series.
  private var selections: [SeriesSelection] {
    guard let scrubDate else { return [] }
    return series.compactMap { s in
      guard let point = s.dataPoints.min(by: {
        abs($0.date.timeIntervalSince(scrubDate)) < abs($1.date.timeIntervalSince(scrubDate))
      }) else { return nil }
      return SeriesSelection(series: s, point: point)
    }
  }

  struct DataPoint: Identifiable {
    let id: UUID
    let date: Date
    let value: Double
  }

  /// One plotted line in an overlaid chart. `color` drives the direct style for
  /// single-series charts and the categorical scale mapping for multi-series.
  struct Series {
    let name: String
    let color: Color
    let dataPoints: [DataPoint]
  }

  /// A series paired with its nearest point to the current scrub position.
  struct SeriesSelection {
    let series: Series
    let point: DataPoint
  }

  /// Single-series convenience initializer wrapping one accent-colored series.
  init(
    title: String,
    unit: String,
    dataPoints: [DataPoint],
    accentColor: Color,
    yAxisDomain: ClosedRange<Double>? = nil
  ) {
    self.title = title
    self.unit = unit
    self.series = [Series(name: title, color: accentColor, dataPoints: dataPoints)]
    self.yAxisDomain = yAxisDomain
  }

  /// Multi-series initializer for overlaid charts.
  init(
    title: String,
    unit: String,
    series: [Series],
    yAxisDomain: ClosedRange<Double>? = nil
  ) {
    self.title = title
    self.unit = unit
    self.series = series
    self.yAxisDomain = yAxisDomain
  }
}

/// Header row that shows the title, and selected value(s) + timestamp when scrubbing.
private struct MetricChartHeader: View {
  let title: String
  let unit: String
  let series: [MetricChartView.Series]
  let selections: [MetricChartView.SeriesSelection]
  let scrubDate: Date?

  private var isMultiSeries: Bool {
    series.count > 1
  }

  var body: some View {
    HStack(alignment: .firstTextBaseline) {
      Text(title)
        .font(.subheadline)
        .bold()

      Spacer()

      if isMultiSeries {
        multiSeriesReadout
      } else {
        singleSeriesReadout
      }
    }
    .font(.caption)
    .animation(.none, value: selections.map(\.point.id))
  }

  @ViewBuilder
  private var singleSeriesReadout: some View {
    if let selection = selections.first {
      Text("\(selection.point.value, format: .number) \(unit)")
        .bold()
        .foregroundStyle(selection.series.color)
        + Text("  ")
        + Text(selection.point.date, format: .dateTime.month(.abbreviated).day().hour().minute())
        .foregroundStyle(.secondary)
    }
  }

  /// Each series' nearest value, color-coded, with the shared scrub timestamp.
  @ViewBuilder
  private var multiSeriesReadout: some View {
    if !selections.isEmpty {
      VStack(alignment: .trailing, spacing: 2) {
        ForEach(selections, id: \.series.name) { selection in
          HStack(spacing: 4) {
            Text(selection.series.name)
              .foregroundStyle(.secondary)
            Text("\(selection.point.value, format: .number)")
              .bold()
              .foregroundStyle(selection.series.color)
          }
        }
        if let scrubDate {
          Text(scrubDate, format: .dateTime.month(.abbreviated).day().hour().minute())
            .foregroundStyle(.secondary)
        }
      }
    }
  }
}

/// Chart content with line and point marks.
private struct MetricChartContent: View {
  let title: String
  let series: [MetricChartView.Series]
  let yAxisDomain: ClosedRange<Double>?
  @Binding var selectedDate: Date?
  let ruleDate: Date?
  let isMultiSeries: Bool

  @State private var isScrubbing = false

  private var foregroundStyleFor: (String) -> Color {
    let lookup = Dictionary(uniqueKeysWithValues: series.map { ($0.name, $0.color) })
    return { (name: String) -> Color in lookup[name] ?? .gray }
  }

  var body: some View {
    chart
      .chartOverlay { proxy in
        GeometryReader { geo in
          let plotOriginX = proxy.plotFrame.map { geo[$0].origin.x } ?? 0
          Rectangle()
            .fill(.clear)
            .contentShape(Rectangle())
            .gesture(
              ChartScrubGesture(
                selectedDate: $selectedDate,
                isScrubbing: $isScrubbing,
                proxy: proxy,
                plotOriginX: plotOriginX
              )
            )
        }
      }
      .sensoryFeedback(.impact, trigger: isScrubbing) { old, new in !old && new }
      .preference(key: ChartScrubbingPreferenceKey.self, value: isScrubbing)
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
      ForEach(series, id: \.name) { s in
        ForEach(s.dataPoints) { point in
          if isMultiSeries {
            LineMark(
              x: .value("Time", point.date),
              y: .value(title, point.value)
            )
            .interpolationMethod(.linear)
            .foregroundStyle(by: .value("Series", s.name))

            PointMark(
              x: .value("Time", point.date),
              y: .value(title, point.value)
            )
            .foregroundStyle(by: .value("Series", s.name))
            .symbolSize(30)
          } else {
            LineMark(
              x: .value("Time", point.date),
              y: .value(title, point.value)
            )
            .interpolationMethod(.linear)
            .foregroundStyle(s.color.opacity(0.5))

            PointMark(
              x: .value("Time", point.date),
              y: .value(title, point.value)
            )
            .foregroundStyle(s.color)
            .symbolSize(30)
          }
        }
      }

      if let ruleDate {
        RuleMark(x: .value("Selected", ruleDate))
          .foregroundStyle(.secondary.opacity(0.3))
          .lineStyle(StrokeStyle(dash: [4, 4]))
          .zIndex(-1)
      }
    }

    if isMultiSeries {
      if let yAxisDomain {
        base.chartYScale(domain: yAxisDomain)
          .chartForegroundStyleScale(mapping: foregroundStyleFor)
          .chartLegend(.visible)
      } else {
        base.chartForegroundStyleScale(mapping: foregroundStyleFor)
          .chartLegend(.visible)
      }
    } else if let yAxisDomain {
      base.chartYScale(domain: yAxisDomain)
    } else {
      base
    }
  }
}

// MARK: - Shared Packet-Count Domain

extension [MetricChartView.DataPoint] {
  /// Returns a common Y-axis domain spanning `0 ... max * 1.05` across all given arrays,
  /// or `nil` when there is no positive max (empty or all-zero data).
  static func sharedDomain(for arrays: [[MetricChartView.DataPoint]]) -> ClosedRange<Double>? {
    let maxVal = arrays.flatMap(\.self).map(\.value).max()
    guard let maxVal, maxVal > 0 else { return nil }
    return 0...maxVal * 1.05
  }
}

// MARK: - OCV Chart Domain

extension [Int] {
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
    return Swift.max(0, lo - buffer)...hi + buffer
  }
}

// MARK: - Chart Scrubbing Scroll Lock

private struct ChartScrubbingPreferenceKey: PreferenceKey {
  static let defaultValue = false
  static func reduce(value: inout Bool, nextValue: () -> Bool) {
    value = value || nextValue()
  }
}

extension View {
  /// Apply to a `List` or `ScrollView` containing `MetricChartView`s to disable
  /// scrolling while the user is long-press scrubbing a chart.
  func chartScrubbingScrollLock() -> some View {
    modifier(ChartScrubbingScrollLockModifier())
  }
}

private struct ChartScrubbingScrollLockModifier: ViewModifier {
  @State private var isScrubbing = false

  func body(content: Content) -> some View {
    content
      .onPreferenceChange(ChartScrubbingPreferenceKey.self) { isScrubbing = $0 }
      .scrollDisabled(isScrubbing)
  }
}

// MARK: - Chart Scrub Gesture

/// UIKit-backed long-press-then-drag gesture for chart scrubbing.
/// Uses `UILongPressGestureRecognizer` because SwiftUI gestures block the parent
/// scroll view's pan recognizer regardless of `.simultaneousGesture` usage.
/// The UIKit recognizer's delegate allows proper simultaneous recognition,
/// and its `.changed` state reports continuous location updates after recognition.
private struct ChartScrubGesture: UIGestureRecognizerRepresentable {
  @Binding var selectedDate: Date?
  @Binding var isScrubbing: Bool
  let proxy: ChartProxy
  let plotOriginX: CGFloat

  func makeUIGestureRecognizer(context: Context) -> UILongPressGestureRecognizer {
    let recognizer = UILongPressGestureRecognizer()
    recognizer.minimumPressDuration = 0.25
    recognizer.delegate = context.coordinator
    return recognizer
  }

  func handleUIGestureRecognizerAction(_ recognizer: UILongPressGestureRecognizer, context: Context) {
    switch recognizer.state {
    case .began:
      isScrubbing = true
      fallthrough
    case .changed:
      let x = context.converter.localLocation.x - plotOriginX
      if let date: Date = proxy.value(atX: x) {
        selectedDate = date
      }
    case .ended, .cancelled, .failed:
      isScrubbing = false
      selectedDate = nil
    default:
      break
    }
  }

  func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
    Coordinator()
  }

  final class Coordinator: NSObject, UIGestureRecognizerDelegate {
    func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
      !(otherGestureRecognizer is UIScreenEdgePanGestureRecognizer)
    }
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
