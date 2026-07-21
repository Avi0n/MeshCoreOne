import CoreLocation
import MapKit
import MC1Services
import SwiftUI

/// Map content displaying MC1MapView with contact/discovered points and popover callouts
struct MapContentView: View {
  @Environment(\.appState) private var appState
  @Environment(\.colorScheme) private var colorScheme
  @Bindable var viewModel: MapViewModel
  let mapStyleSelection: MapStyleSelection
  let showLabels: Bool
  let isNorthLocked: Bool
  @Binding var selectedCallout: MapCalloutSelection?
  @Binding var selectedPointScreenPosition: CGPoint?
  @Binding var isStyleLoaded: Bool
  @Binding var isCenteredOnUser: Bool
  let isAddingDiscovered: Bool
  let onShowContactDetail: (ContactDTO) -> Void
  let onNavigateToChat: (ContactDTO) -> Void
  let onShowDiscoveredDetail: (DiscoveredNodeDTO) -> Void
  let onAddDiscovered: (DiscoveredNodeDTO) -> Void
  let onPersistCamera: (MKCoordinateRegion) -> Void

  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var mapColorSchemeRaw = AppStorageKey.defaultMapColorSchemePreference

  @State private var selectedDroppedPin: DroppedPinSelection?

  private var mapIsDark: Bool {
    let preference = AppColorSchemePreference(rawValue: mapColorSchemeRaw) ?? .system
    return resolvedMapIsDark(preference: preference, colorScheme: colorScheme)
  }

  var body: some View {
    MC1MapView(
      points: viewModel.mapPoints,
      lines: [],
      mapStyle: mapStyleSelection,
      isDarkMode: mapIsDark,
      isOffline: !appState.offlineMapService.isNetworkAvailable,
      showLabels: showLabels,
      showsUserLocation: true,
      isInteractive: true,
      showsScale: true,
      isNorthLocked: isNorthLocked,
      cameraRegion: $viewModel.cameraRegion,
      cameraRegionVersion: viewModel.cameraRegionVersion,
      onPointTap: { point, screenPosition in
        if point.pinStyle == .droppedPin {
          selectedCallout = nil
          selectedDroppedPin = DroppedPinSelection(coordinate: point.coordinate)
        } else if let contact = viewModel.contact(forPointID: point.id) {
          selectedDroppedPin = nil
          selectedCallout = .contact(contact)
        } else if let node = viewModel.discovered(forPointID: point.id) {
          selectedDroppedPin = nil
          selectedCallout = .discovered(node)
        } else {
          selectedCallout = nil
        }
        selectedPointScreenPosition = screenPosition
      },
      onMapTap: { _ in
        selectedCallout = nil
        selectedDroppedPin = nil
        selectedPointScreenPosition = nil
      },
      onCameraRegionChange: { region in
        viewModel.cameraRegion = region
        onPersistCamera(region)
        if selectedCallout != nil || selectedDroppedPin != nil {
          selectedCallout = nil
          selectedDroppedPin = nil
          selectedPointScreenPosition = nil
        }
      },
      isStyleLoaded: $isStyleLoaded,
      isCenteredOnUser: $isCenteredOnUser
    )
    .popover(
      item: $selectedCallout,
      attachmentAnchor: .rect(.rect(CGRect(
        origin: selectedPointScreenPosition ?? .zero,
        size: CGSize(width: 1, height: 1)
      ))),
      arrowEdge: .bottom
    ) { selection in
      switch selection {
      case let .contact(contact):
        ContactCalloutContent(
          contact: contact,
          onDetail: { onShowContactDetail(contact) },
          onMessage: { onNavigateToChat(contact) }
        )
        .presentationCompactAdaptation(.popover)
      case let .discovered(node):
        DiscoveredNodeCalloutContent(
          node: node,
          isAdding: isAddingDiscovered,
          onDetail: { onShowDiscoveredDetail(node) },
          onAdd: { onAddDiscovered(node) }
        )
        .presentationCompactAdaptation(.popover)
      }
    }
    .popover(
      item: $selectedDroppedPin,
      attachmentAnchor: .rect(.rect(CGRect(
        origin: selectedPointScreenPosition ?? .zero,
        size: CGSize(width: 1, height: 1)
      ))),
      arrowEdge: .bottom
    ) { selection in
      DroppedPinCallout(coordinate: selection.coordinate) {
        viewModel.clearFocusedPin()
        selectedDroppedPin = nil
      }
      .presentationCompactAdaptation(.popover)
    }
    .overlay {
      if !isStyleLoaded {
        ProgressView()
          .scaleEffect(1.5)
      } else if viewModel.isLoading {
        MapLoadingOverlay()
      }
    }
  }
}

// MARK: - Loading Overlay

private struct MapLoadingOverlay: View {
  var body: some View {
    ZStack {
      Color.black.opacity(0.1)
      ProgressView()
        .padding()
        .background(.regularMaterial, in: .rect(cornerRadius: 8))
    }
  }
}

private struct DroppedPinSelection: Identifiable {
  let id = UUID()
  let coordinate: CLLocationCoordinate2D
}
