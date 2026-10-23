import MapKit
import MC1Services
import SwiftUI

struct ContactLocationSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  let currentContact: ContactDTO

  @Binding var showFullMap: Bool
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void

  var body: some View {
    Section {
      ZStack(alignment: .topTrailing) {
        MC1MapView(
          points: [MapPoint(
            id: currentContact.id,
            coordinate: currentContact.coordinate,
            pinStyle: currentContact.type.pinStyle,
            label: currentContact.displayName,
            isClusterable: false,
            hopIndex: nil,
            badgeText: nil
          )],
          lines: [],
          mapStyle: .standard,
          isOffline: !appState.offlineMapService.isNetworkAvailable,
          showLabels: false,
          showsUserLocation: false,
          isInteractive: false,
          showsScale: false,
          cameraRegion: .constant(MKCoordinateRegion(
            center: currentContact.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
          )),
          cameraRegionVersion: currentContact.latitude.hashValue ^ currentContact.longitude.hashValue,
          onPointTap: { _, _ in showFullMap = true },
          onMapTap: { _ in showFullMap = true },
          onCameraRegionChange: nil
        )

        Image(systemName: "arrow.up.left.and.arrow.down.right")
          .font(.caption.weight(.semibold))
          .padding(6)
          .background(.regularMaterial, in: .rect(cornerRadius: 6))
          .padding(8)
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
      .frame(height: 200)
      .clipShape(.rect(cornerRadius: 12))
      .listRowInsets(EdgeInsets())
      .listRowBackground(Color.clear)
      .padding(.bottom, 8)
      .listRowSeparator(.hidden)
      .modifier(ContactDetailRowPosition(section: .location, onPosition: onRowPosition))

      HStack {
        Text(L10n.Contacts.Contacts.Detail.coordinates)
        Spacer()
        Text("\(currentContact.latitude, format: .number.precision(.fractionLength(4))), \(currentContact.longitude, format: .number.precision(.fractionLength(4)))")
          .foregroundStyle(.secondary)
      }
      .listRowBackground(
        UnevenRoundedRectangle(topLeadingRadius: 10, topTrailingRadius: 10)
          .fill(theme.surfaces?.card ?? Color(.secondarySystemGroupedBackground))
      )

      Button {
        openInMaps()
      } label: {
        Label(L10n.Contacts.Contacts.Detail.openInMaps, systemImage: "map")
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.location)
    }
  }

  private func openInMaps() {
    let mapItem = MKMapItem(placemark: MKPlacemark(coordinate: currentContact.coordinate))
    mapItem.name = currentContact.displayName
    mapItem.openInMaps()
  }
}
