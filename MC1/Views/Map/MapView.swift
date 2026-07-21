import MapKit
import MC1Services
import SwiftUI

/// Map view displaying contacts and optionally discovered nodes with their locations
struct MapView: View {
  @Environment(\.appState) private var appState
  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.showDiscoveredNodesOnMap.rawValue)
  private var showDiscoveredNodesOnMap = AppStorageKey.defaultShowDiscoveredNodesOnMap
  @SceneStorage(SceneStorageKey.mapCameraRegion.rawValue) private var savedCameraRegion = ""
  @State private var viewModel = MapViewModel()
  @State private var selectedCallout: MapCalloutSelection?
  @State private var selectedPointScreenPosition: CGPoint?
  @State private var selectedContactForDetail: ContactDTO?
  @State private var selectedDiscoveredForDetail: DiscoveredNodeDTO?
  @State private var addingDiscoveredNodeID: UUID?
  @State private var isStyleLoaded = false

  private var isAddingDiscovered: Bool {
    addingDiscoveredNodeID != nil
  }

  var body: some View {
    NavigationStack {
      MapCanvasView(
        viewModel: viewModel,
        mapStyleSelection: $mapStyleSelection,
        showLabels: $showLabels,
        isNorthLocked: $isNorthLocked,
        selectedCallout: $selectedCallout,
        selectedPointScreenPosition: $selectedPointScreenPosition,
        isStyleLoaded: $isStyleLoaded,
        isAddingDiscovered: isAddingDiscovered,
        onShowContactDetail: { showContactDetail($0) },
        onNavigateToChat: { navigateToChat(with: $0) },
        onShowDiscoveredDetail: { showDiscoveredDetail($0) },
        onAddDiscovered: { addDiscoveredNode($0) },
        onCenterOnUser: { centerOnUserLocation() },
        onClearSelection: { clearSelection() },
        onPersistCamera: { savedCameraRegion = MapCameraStore.encode($0) }
      )
      .toolbar {
        bleStatusToolbarItem()
        ToolbarItem(placement: .topBarTrailing) {
          MapRefreshButton(
            isLoading: viewModel.isLoading,
            onRefresh: {
              viewModel.cancelPendingReload()
              await viewModel.loadMapData(
                includeDiscovered: showDiscoveredNodesOnMap,
                showsLoadingChrome: true
              )
            }
          )
        }
      }
      .task {
        appState.locationService.requestPermissionIfNeeded()
        appState.locationService.requestLocation()
        configureViewModel()
        await viewModel.loadMapData(
          includeDiscovered: showDiscoveredNodesOnMap,
          showsLoadingChrome: true
        )
        // On first appearance the view model has no camera region; restoring here
        // keeps the map where the user left it across launches instead of re-framing.
        viewModel.applyInitialCamera(
          saved: MapCameraStore.decode(savedCameraRegion),
          hasPendingFocus: appState.navigation.pendingMapFocus != nil
        )
      }
      .onChange(of: showDiscoveredNodesOnMap) { _, includeDiscovered in
        clearSelection()
        selectedDiscoveredForDetail = nil
        viewModel.scheduleCoalescedReload(includeDiscovered: includeDiscovered)
      }
      .onChange(of: appState.contactsVersion) { _, _ in
        viewModel.scheduleCoalescedReload(includeDiscovered: showDiscoveredNodesOnMap)
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        configureViewModel()
        viewModel.scheduleCoalescedReload(includeDiscovered: showDiscoveredNodesOnMap)
      }
      .onChange(of: appState.navigation.pendingMapFocus, initial: true) { _, request in
        guard let request else { return }
        viewModel.focusOnCoordinate(request.coordinate)
        appState.navigation.clearPendingMapFocus()
      }
      .sheet(item: $selectedContactForDetail) { contact in
        ContactDetailSheet(
          contact: contact,
          onMessage: { navigateToChat(with: contact) },
          onDelete: {
            Task {
              await viewModel.loadMapData(
                includeDiscovered: showDiscoveredNodesOnMap,
                showsLoadingChrome: false
              )
            }
          }
        )
        .presentationDetents([.large])
      }
      .sheet(item: $selectedDiscoveredForDetail) { node in
        DiscoveredNodeDetailSheet(
          node: node,
          isAdding: addingDiscoveredNodeID == node.id,
          onAdd: { addDiscoveredNode(node) }
        )
        .presentationDetents([.medium, .large])
      }
      .errorAlert($viewModel.errorMessage)
      .liquidGlassToolbarBackground()
    }
  }

  // MARK: - Actions

  private func configureViewModel() {
    viewModel.configure(
      dataStore: { [appState] in appState.offlineDataStore },
      radioID: { [appState] in appState.currentRadioID }
    )
  }

  private func clearSelection() {
    selectedCallout = nil
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

  private func showDiscoveredDetail(_ node: DiscoveredNodeDTO) {
    clearSelection()
    selectedDiscoveredForDetail = node
  }

  private func addDiscoveredNode(_ node: DiscoveredNodeDTO) {
    guard addingDiscoveredNodeID == nil else { return }
    guard let contactService = appState.services?.contactService else {
      viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.notConnected
      return
    }
    clearSelection()
    addingDiscoveredNodeID = node.id
    Task {
      defer { addingDiscoveredNodeID = nil }
      do {
        try await contactService.addOrUpdateContact(
          radioID: node.radioID,
          contact: node.makeContactFrame()
        )
        await viewModel.loadMapData(
          includeDiscovered: showDiscoveredNodesOnMap,
          showsLoadingChrome: false
        )
        selectedDiscoveredForDetail = nil
      } catch ContactServiceError.contactTableFull {
        let maxContacts = appState.connectedDevice?.maxContacts
        if let maxContacts {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
        } else {
          viewModel.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
        }
      } catch {
        viewModel.errorMessage = error.userFacingMessage
      }
    }
  }

  private func centerOnUserLocation() -> Bool {
    appState.centerOnUserLocation { viewModel.setCameraRegion($0) }
  }
}

// MARK: - Map Refresh Button

private struct MapRefreshButton: View {
  let isLoading: Bool
  let onRefresh: () async -> Void

  var body: some View {
    Button(L10n.Map.Map.Controls.refresh, systemImage: "arrow.clockwise") {
      Task {
        await onRefresh()
      }
    }
    .labelStyle(.iconOnly)
    .disabled(isLoading)
    .opacity(isLoading ? 0 : 1)
    .overlay {
      if isLoading {
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
