import SwiftUI

/// Displays a trend arrow and delta value next to a status metric.
struct StatusDeltaView: View {
  let delta: Double
  /// Whether higher values are better (true for battery/SNR, false for noise floor)
  let higherIsBetter: Bool
  let unit: String
  /// Number of decimal places to display (0 for integers like mV/dBm, 1 for floats like SNR)
  let fractionDigits: Int

  var body: some View {
    HStack(spacing: 2) {
      Image(systemName: delta > 0 ? "arrow.up" : "arrow.down")
        .imageScale(.small)
      Text("\(abs(delta).formatted(.number.precision(.fractionLength(fractionDigits))))\(unit)")
    }
    .font(.caption)
    .foregroundStyle(deltaColor)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(accessibilityDescription)
  }

  private var isImprovement: Bool {
    higherIsBetter ? delta > 0 : delta < 0
  }

  private var deltaColor: Color {
    if abs(delta) < 0.01 { return .secondary }
    return isImprovement ? .green : .orange
  }

  private var accessibilityDescription: String {
    let direction = delta > 0
      ? L10n.RemoteNodes.RemoteNodes.History.A11y.increased
      : L10n.RemoteNodes.RemoteNodes.History.A11y.decreased
    let quality = isImprovement
      ? L10n.RemoteNodes.RemoteNodes.History.A11y.improved
      : L10n.RemoteNodes.RemoteNodes.History.A11y.degraded
    let formatted = abs(delta).formatted(.number.precision(.fractionLength(fractionDigits)))
    return L10n.RemoteNodes.RemoteNodes.History.A11y.deltaDescription(quality, direction, formatted, unit)
  }
}
