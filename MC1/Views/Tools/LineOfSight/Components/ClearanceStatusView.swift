import MC1Services
import SwiftUI

/// Displays clearance status with appropriate icon, color, and clearance percentage
struct ClearanceStatusView: View {
  let status: ClearanceStatus
  let clearancePercent: Double

  var body: some View {
    HStack(spacing: 6) {
      Image(systemName: status.iconName)
        .foregroundStyle(status.color)
        .font(.body.weight(.semibold))

      Text(status.localizedName)
        .font(.headline)

      // Show clearance percentage only for non-blocked statuses
      if status != .blocked {
        Text("·")
          .foregroundStyle(.secondary)
        Text(L10n.Tools.Tools.LineOfSight.clearancePercent(LOSFormatters.formatClearancePercent(clearancePercent)))
          .font(.subheadline)
          .foregroundStyle(.secondary)
      }
    }
  }
}

// MARK: - Preview

#Preview("Clear") {
  ClearanceStatusView(status: .clear, clearancePercent: 92)
}

#Preview("Marginal") {
  ClearanceStatusView(status: .marginal, clearancePercent: 72)
}

#Preview("Partial Obstruction") {
  ClearanceStatusView(status: .partialObstruction, clearancePercent: 47)
}

#Preview("Blocked") {
  ClearanceStatusView(status: .blocked, clearancePercent: -15)
}
