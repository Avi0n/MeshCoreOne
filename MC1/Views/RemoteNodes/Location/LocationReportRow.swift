import CoreLocation
import MC1Services
import SwiftUI

/// One row in the location History list: a leading tick, a relative recency, and
/// an absolute timestamp with coordinates. The latest report is tinted to match
/// its hero marker on the map. Tapping the row opens the full-screen map.
struct LocationReportRow: View {
  let snapshot: NodeStatusSnapshotDTO
  let isLatest: Bool
  /// Fires when the row is tapped; the host pushes the full-screen map.
  let onTap: () -> Void

  @Environment(\.appTheme) private var theme

  /// Diameter of the leading timeline tick.
  private static let tickSize: CGFloat = 9
  /// Joins the absolute time and coordinates on the detail line.
  private static let separator = " · "

  private var emphasisColor: Color {
    isLatest ? theme.accentColor : .primary
  }

  /// "Jul 13, 07:41 · 37.7847, -122.4012 · 42 m". Coordinates fall back to the
  /// stored raw pair if `validCoordinate` is nil, though the list only builds
  /// rows for snapshots that have a valid fix. Altitude is appended only when the
  /// fix carried one; "no altitude" is distinct from sea level.
  private var detailLine: String {
    let time = LocationReportFormat.absoluteTime(for: snapshot.timestamp)
    guard let coordinate = snapshot.validCoordinate else { return time }
    var line = time + Self.separator + LocationReportFormat.coordinates(coordinate)
    if let altitude = snapshot.altitude {
      line += Self.separator + LocationReportFormat.altitude(altitude)
    }
    return line
  }

  var body: some View {
    Button(action: onTap) {
      HStack(alignment: .center, spacing: 12) {
        Circle()
          .fill(isLatest ? theme.accentColor : Color.secondary)
          .frame(width: Self.tickSize, height: Self.tickSize)

        VStack(alignment: .leading, spacing: 2) {
          Text(LocationReportFormat.relativeTime(for: snapshot.timestamp, relativeTo: .now))
            .font(.subheadline.weight(isLatest ? .semibold : .regular))
            .foregroundStyle(emphasisColor)
          Text(detailLine)
            .font(.caption)
            .foregroundStyle(.secondary)
        }

        Spacer(minLength: 0)

        Image(systemName: "chevron.forward")
          .font(.caption2.weight(.semibold))
          .foregroundStyle(.tertiary)
      }
      .contentShape(.rect)
    }
    .buttonStyle(.plain)
    .accessibilityElement(children: .combine)
  }
}
