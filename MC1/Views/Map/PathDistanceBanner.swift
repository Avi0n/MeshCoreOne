import MapKit
import SwiftUI

/// Path summary shown in place of the map sheet's navigation title: hop count
/// and, when at least two nodes have coordinates, the drawn-path distance.
struct PathDistanceBanner: View {
  private static let horizontalPadding: CGFloat = 16
  private static let verticalPadding: CGFloat = 8

  let hopCount: Int
  let totalPathDistance: CLLocationDistance?

  var body: some View {
    HStack(spacing: 4) {
      Text(L10n.Contacts.Contacts.Trace.Map.hops(hopCount))
      if let distance = totalPathDistance {
        Text("•")
        Text(Measurement(value: distance, unit: UnitLength.meters),
             format: .measurement(width: .abbreviated, usage: .road))
      }
    }
    .font(.subheadline.weight(.medium))
    .padding(.horizontal, Self.horizontalPadding)
    .padding(.vertical, Self.verticalPadding)
    .liquidGlass(in: .capsule)
  }
}
