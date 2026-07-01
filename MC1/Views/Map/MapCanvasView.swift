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
  @Binding var selectedCalloutContact: ContactDTO?
  @Binding var selectedPointScreenPosition: CGPoint?
  @Binding var isStyleLoaded: Bool
  let onShowContactDetail: (ContactDTO) -> Void
  let onNavigateToChat: (ContactDTO) -> Void
  let onCenterOnUser: () -> Void
  let onClearSelection: () -> Void
  let onPersistCamera: (MKCoordinateRegion) -> Void

  var body: some View {
    ZStack {
      MapContentView(
        viewModel: viewModel,
        mapStyleSelection: mapStyleSelection,
        showLabels: showLabels,
        selectedCalloutContact: $selectedCalloutContact,
        selectedPointScreenPosition: $selectedPointScreenPosition,
        isStyleLoaded: $isStyleLoaded,
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
          isNorthLocked: $viewModel.isNorthLocked,
          showingLayersMenu: $viewModel.showingLayersMenu,
          showLabels: $showLabels,
          contactsEmpty: viewModel.contactsWithLocation.isEmpty,
          onLocationTap: { onCenterOnUser() },
          onClearSelection: onClearSelection,
          onCenterAll: { viewModel.centerOnAllContacts() }
        )
      }

      // Layers menu overlay
      if viewModel.showingLayersMenu {
        Button {
          withAnimation {
            viewModel.showingLayersMenu = false
          }
        } label: {
          Color.black.opacity(0.3)
            .ignoresSafeArea()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.Map.Map.Common.dismissOverlay)

        VStack {
          Spacer()
          HStack {
            Spacer()
            LayersMenu(
              selection: $mapStyleSelection,
              isPresented: $viewModel.showingLayersMenu,
              viewportBounds: viewModel.cameraRegion?.toMLNCoordinateBounds()
            )
            .padding(.trailing, 72)
            .padding(.bottom)
          }
        }
      }
    }
  }
}

// MARK: - Map Controls

private struct MapCanvasControls: View {
  @Binding var isNorthLocked: Bool
  @Binding var showingLayersMenu: Bool
  @Binding var showLabels: Bool
  let contactsEmpty: Bool
  let onLocationTap: () -> Void
  let onClearSelection: () -> Void
  let onCenterAll: () -> Void

  var body: some View {
    HStack {
      Spacer()
      MapControlsToolbar(
        onLocationTap: onLocationTap,
        isNorthLocked: $isNorthLocked,
        showLabels: $showLabels,
        showingLayersMenu: $showingLayersMenu
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
