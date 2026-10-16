import SwiftUI

/// Popover shown when a location report pin is tapped: when the node was there,
/// and its altitude when known. A report is an observation, so the callout states
/// *when*, not a name or an action.
struct LocationReportCallout: View {
  let report: LocationPathMapBuilder.LocationReport

  private var detail: String {
    var line = LocationReportFormat.absoluteTime(for: report.timestamp)
    if let altitude = report.altitude {
      line += " · " + LocationReportFormat.altitude(altitude)
    }
    return line
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Text(LocationReportFormat.relativeTime(for: report.timestamp, relativeTo: .now))
        .font(.subheadline.weight(.semibold))
      Text(detail)
        .font(.caption)
        .foregroundStyle(.secondary)
    }
    .padding(12)
    .frame(minWidth: 160, alignment: .leading)
  }
}
