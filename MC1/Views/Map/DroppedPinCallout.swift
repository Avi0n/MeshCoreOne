import MapKit
import SwiftUI

/// Popover callout for a chat-dropped map pin: the coordinate, an Apple Maps hand-off,
/// and a clear action. Sized like the contact callout.
struct DroppedPinCallout: View {
  let coordinate: CLLocationCoordinate2D
  let onClear: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(coordinate.formattedString)
        .font(.headline)
        .monospaced()

      Divider()

      VStack(spacing: 6) {
        Button(L10n.Contacts.Contacts.Detail.openInMaps, systemImage: "map") {
          let mapItem = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
          mapItem.openInMaps()
        }
        .buttonStyle(.bordered)

        Button(L10n.Tools.Tools.LineOfSight.clear, systemImage: "xmark", action: onClear)
          .buttonStyle(.bordered)
      }
      .frame(maxWidth: .infinity)
    }
    .padding(12)
    .frame(minWidth: 160)
  }
}

#Preview {
  DroppedPinCallout(
    coordinate: CLLocationCoordinate2D(latitude: 37.3349, longitude: -122.00902),
    onClear: {}
  )
  .background(Color(.systemBackground))
}
