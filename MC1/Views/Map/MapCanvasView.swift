import MapKit
import MapLibre
import MC1Services
import SwiftUI

/// Canvas wrapping the map content with offline badge, floating controls, and layers menu overlay
struct MapCanvasView: View {
  @Environment(\.appState) private var appState
  @Bindable var viewModel: MapViewModel
  @Binding var mapStyleSelection: MapStyleSelection
  @Binding var showLabels: Bool
  @Binding var isNorthLocked: Bool
  @Binding var selectedCalloutContact: ContactDTO?
  @Binding var selectedPointScreenPosition: CGPoint?
  @Binding var isStyleLoaded: Bool
  let onShowContactDetail: (ContactDTO) -> Void
  let onNavigateToChat: (ContactDTO) -> Void
  let onCenterOnUser: () -> Void
  let onClearSelection: () -> Void
  let onPersistCamera: (MKCoordinateRegion) -> Void

  @State private var isCenteredOnUser = false

  var body: some View {
    ZStack {
      MapContentView(
        viewModel: viewModel,
        mapStyleSelection: mapStyleSelection,
        showLabels: showLabels,
        isNorthLocked: isNorthLocked,
        selectedCalloutContact: $selectedCalloutContact,
        selectedPointScreenPosition: $selectedPointScreenPosition,
        isStyleLoaded: $isStyleLoaded,
        isCenteredOnUser: $isCenteredOnUser,
        onShowContactDetail: onShowContactDetail,
        onNavigateToChat: onNavigateToChat,
        onPersistCamera: onPersistCamera
      )
      .ignoresSafeArea()

      // Offline badge
      if !appState.offlineMapService.isNetworkAvailable {
        OfflineBadge()
      }

      // Floating controls
      VStack {
        Spacer()
        MapCanvasControls(
          isNorthLocked: $isNorthLocked,
          showLabels: $showLabels,
          mapStyleSelection: $mapStyleSelection,
          isCenteredOnUser: isCenteredOnUser,
          viewportBounds: viewModel.cameraRegion?.toMLNCoordinateBounds(),
          contactsEmpty: viewModel.contactsWithLocation.isEmpty,
          onLocationTap: {
            if appState.bestAvailableLocation != nil {
              isCenteredOnUser = true
            }
            onCenterOnUser()
          },
          onClearSelection: onClearSelection,
          onCenterAll: { viewModel.centerOnAllContacts() }
        )
      }
    }
  }
}

// MARK: - Map Controls

private struct MapCanvasControls: View {
  @Binding var isNorthLocked: Bool
  @Binding var showLabels: Bool
  @Binding var mapStyleSelection: MapStyleSelection
  let isCenteredOnUser: Bool
  let viewportBounds: MLNCoordinateBounds?
  let contactsEmpty: Bool
  let onLocationTap: () -> Void
  let onClearSelection: () -> Void
  let onCenterAll: () -> Void

  var body: some View {
    HStack {
      Spacer()
      MapControlsToolbar(
        onLocationTap: onLocationTap,
        isCenteredOnUser: isCenteredOnUser,
        isNorthLocked: $isNorthLocked,
        showLabels: $showLabels,
        mapStyleSelection: $mapStyleSelection,
        viewportBounds: viewportBounds
      ) {
        CenterAllButton(
          isEmpty: contactsEmpty,
          onClearSelection: onClearSelection,
          onCenterAll: onCenterAll
        )
      }
    }
  }
}

// MARK: - Control Buttons

private struct CenterAllButton: View {
  let isEmpty: Bool
  let onClearSelection: () -> Void
  let onCenterAll: () -> Void

  var body: some View {
    Button(L10n.Map.Map.Controls.centerAll, systemImage: "arrow.up.left.and.arrow.down.right") {
      onClearSelection()
      onCenterAll()
    }
    .mapControlButton(tint: isEmpty ? .secondary : .primary)
    .disabled(isEmpty)
  }
}
