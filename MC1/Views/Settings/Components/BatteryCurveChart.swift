import Charts
import SwiftUI

/// Visual representation of an OCV discharge curve
struct BatteryCurveChart: View {
  let ocvArray: [Int]

  private var dataPoints: [DataPoint] {
    ocvArray.enumerated().map { index, voltage in
      DataPoint(
        voltage: Double(voltage) / 1000.0, // Convert to volts
        percent: (10 - index) * 10 // 100, 90, 80, ... 0
      )
    }
  }

  /// Y axis lower bound: min voltage - 100mV, rounded down to nearest 0.1V
  private var yAxisMin: Double {
    guard let minMV = ocvArray.min() else { return 1.0 }
    return (Double(minMV - 100) / 1000.0).rounded(.down, precision: 1)
  }

  /// Y axis upper bound: max voltage + 100mV, rounded up to nearest 0.1V
  private var yAxisMax: Double {
    guard let maxMV = ocvArray.max() else { return 4.5 }
    return (Double(maxMV + 100) / 1000.0).rounded(.up, precision: 1)
  }

  var body: some View {
    Chart(dataPoints) { point in
      AreaMark(
        x: .value("Percent", point.percent),
        yStart: .value("Baseline", yAxisMin),
        yEnd: .value("Voltage", point.voltage)
      )
      .foregroundStyle(.blue.opacity(0.2))
      .interpolationMethod(.monotone)

      LineMark(
        x: .value("Percent", point.percent),
        y: .value("Voltage", point.voltage)
      )
      .foregroundStyle(.blue)
      .interpolationMethod(.monotone)
    }
    .chartXScale(domain: 0...100)
    .chartXAxis {
      AxisMarks(values: [0, 25, 50, 75, 100])
    }
    .chartYScale(domain: yAxisMin...yAxisMax)
    .chartXAxisLabel(L10n.Settings.Chart.percent)
    .chartYAxisLabel(L10n.Settings.Chart.voltage)
    .accessibilityLabel(L10n.Settings.Chart.accessibility)
    .frame(height: 150)
  }
}

private struct DataPoint: Identifiable {
  var id: Int {
    percent
  }

  let voltage: Double
  let percent: Int
}

private extension Double {
  func rounded(_ rule: FloatingPointRoundingRule, precision: Int) -> Double {
    let multiplier = pow(10.0, Double(precision))
    return (self * multiplier).rounded(rule) / multiplier
  }
}

#Preview {
  BatteryCurveChart(ocvArray: [4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100])
    .padding()
}
