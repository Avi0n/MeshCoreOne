import SwiftUI
import MC1Services
import CoreLocation
import MapKit

/// Map content displaying MC1MapView with contact points and popover callouts
struct MapContentView: View {
    @Environment(\.appState) private var appState
    @Environment(\.colorScheme) private var colorScheme
    @Bindable var viewModel: MapViewModel
    let mapStyleSelection: MapStyleSelection
    let showLabels: Bool
    @Binding var selectedCalloutContact: ContactDTO?
    @Binding var selectedPointScreenPosition: CGPoint?
    @Binding var isStyleLoaded: Bool
    let onShowContactDetail: (ContactDTO) -> Void
    let onNavigateToChat: (ContactDTO) -> Void
    let onPersistCamera: (MKCoordinateRegion) -> Void

    @State private var selectedDroppedPin: DroppedPinSelection?

    var body: some View {
        MC1MapView(
            points: viewModel.mapPoints,
            lines: [],
            mapStyle: mapStyleSelection,
            isDarkMode: colorScheme == .dark,
            isOffline: !appState.offlineMapService.isNetworkAvailable,
            showLabels: showLabels,
            showsUserLocation: true,
            isInteractive: true,
            showsScale: true,
            isNorthLocked: viewModel.isNorthLocked,
            cameraRegion: $viewModel.cameraRegion,
            cameraRegionVersion: viewModel.cameraRegionVersion,
            onPointTap: { point, screenPosition in
                if point.pinStyle == .droppedPin {
                    selectedCalloutContact = nil
                    selectedDroppedPin = DroppedPinSelection(coordinate: point.coordinate)
                } else {
                    selectedDroppedPin = nil
                    selectedCalloutContact = viewModel.contactsWithLocation.first { $0.id == point.id }
                }
                selectedPointScreenPosition = screenPosition
            },
            onMapTap: { _ in
                selectedCalloutContact = nil
                selectedDroppedPin = nil
                selectedPointScreenPosition = nil
            },
            onCameraRegionChange: { region in
                viewModel.cameraRegion = region
                onPersistCamera(region)
                if selectedCalloutContact != nil || selectedDroppedPin != nil {
                    selectedCalloutContact = nil
                    selectedDroppedPin = nil
                    selectedPointScreenPosition = nil
                }
            },
            isStyleLoaded: $isStyleLoaded
        )
        .popover(
            item: $selectedCalloutContact,
            attachmentAnchor: .rect(.rect(CGRect(
                origin: selectedPointScreenPosition ?? .zero,
                size: CGSize(width: 1, height: 1)
            ))),
            arrowEdge: .bottom
        ) { contact in
            ContactCalloutContent(
                contact: contact,
                onDetail: { onShowContactDetail(contact) },
                onMessage: { onNavigateToChat(contact) }
            )
            .presentationCompactAdaptation(.popover)
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
