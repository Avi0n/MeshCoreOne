import SwiftUI
import MapKit
import MC1Services

/// Map view displaying contacts with their locations
struct MapView: View {
    @Environment(\.appState) private var appState
    @AppStorage("mapStyleSelection") private var mapStyleSelection: MapStyleSelection = .standard
    @AppStorage("mapShowLabels") private var showLabels = true
    @State private var viewModel = MapViewModel()
    @State private var selectedCalloutContact: ContactDTO?
    @State private var selectedPointScreenPosition: CGPoint?
    @State private var selectedContactForDetail: ContactDTO?
    @State private var isStyleLoaded = false

    var body: some View {
        NavigationStack {
            MapCanvasView(
                viewModel: viewModel,
                mapStyleSelection: $mapStyleSelection,
                showLabels: $showLabels,
                selectedCalloutContact: $selectedCalloutContact,
                selectedPointScreenPosition: $selectedPointScreenPosition,
                isStyleLoaded: $isStyleLoaded,
                onShowContactDetail: { showContactDetail($0) },
                onNavigateToChat: { navigateToChat(with: $0) },
                onCenterOnUser: { centerOnUserLocation() },
                onClearSelection: { clearSelection() }
            )
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    BLEStatusIndicatorView()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    MapRefreshButton(viewModel: viewModel)
                }
            }
            .task {
                appState.locationService.requestPermissionIfNeeded()
                appState.locationService.requestLocation()
                viewModel.configure(appState: appState)
                await viewModel.loadContactsWithLocation()
                // A pending or active chat-coordinate focus owns the camera; do not
                // override it with the all-contacts framing on first appear.
                if appState.navigation.pendingMapFocus == nil && viewModel.focusedPin == nil {
                    viewModel.centerOnAllContacts()
                }
            }
            .onChange(of: appState.navigation.pendingMapFocus, initial: true) { _, request in
                guard let request else { return }
                viewModel.focusOnCoordinate(request.coordinate)
                appState.navigation.clearPendingMapFocus()
            }
            .sheet(item: $selectedContactForDetail) { contact in
                ContactDetailSheet(
                    contact: contact,
                    onMessage: { navigateToChat(with: contact) }
                )
                .presentationDetents([.large])
            }
            .liquidGlassToolbarBackground()
        }
    }

    // MARK: - Actions

    private func clearSelection() {
        selectedCalloutContact = nil
        selectedPointScreenPosition = nil
    }

    private func navigateToChat(with contact: ContactDTO) {
        clearSelection()
        appState.navigation.navigateToChat(with: contact)
    }

    private func showContactDetail(_ contact: ContactDTO) {
        clearSelection()
        selectedContactForDetail = contact
    }

    private func centerOnUserLocation() {
        guard let location = appState.bestAvailableLocation else { return }
        let span = MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02)
        viewModel.setCameraRegion(MKCoordinateRegion(center: location.coordinate, span: span))
    }
}

// MARK: - Map Refresh Button

private struct MapRefreshButton: View {
    var viewModel: MapViewModel

    var body: some View {
        Button(L10n.Map.Map.Controls.refresh, systemImage: "arrow.clockwise") {
            Task {
                await viewModel.loadContactsWithLocation()
            }
        }
        .labelStyle(.iconOnly)
        .disabled(viewModel.isLoading)
        .opacity(viewModel.isLoading ? 0 : 1)
        .overlay {
            if viewModel.isLoading {
                ProgressView()
            }
        }
    }
}

// MARK: - Preview

#Preview {
    MapView()
        .environment(\.appState, AppState())
}
