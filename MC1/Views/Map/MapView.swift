import MapKit
import MC1Services
import SwiftUI

/// Map view displaying contacts and optionally discovered nodes with their locations
struct MapView: View {
  @Environment(\.appState) private var appState
  @AppStorage(AppStorageKey.mapStyleSelection.rawValue) private var mapStyleSelection: MapStyleSelection = .standard
  @AppStorage(AppStorageKey.mapShowLabels.rawValue) private var showLabels = AppStorageKey.defaultMapShowLabels
  @AppStorage(AppStorageKey.mapNorthLocked.rawValue) private var isNorthLocked = AppStorageKey.defaultMapNorthLocked
  @AppStorage(AppStorageKey.mapFilterMainMap.rawValue)
  private var mapFilterRaw: String = ""
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

  private var mapFilter: MapFilterState {
    MapFilterPreferences.state(fromRaw: mapFilterRaw, host: .mainMap)
  }

  private var mapFilterBinding: Binding<MapFilterState> {
    MapFilterPreferences.binding(raw: $mapFilterRaw, host: .mainMap)
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
        filter: MapFilterControl(host: .mainMap, state: mapFilterBinding),
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
                filter: mapFilter,
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
        // Empty stays empty (seed without write); legacy/corrupt loads may persist.
        MapFilterPreferences.ensureMigrated(raw: &mapFilterRaw, host: .mainMap)
        await viewModel.loadMapData(
          filter: mapFilter,
          showsLoadingChrome: true
        )
        // On first appearance the view model has no camera region; restoring here
        // keeps the map where the user left it across launches instead of re-framing.
        viewModel.applyInitialCamera(
          saved: MapCameraStore.decode(savedCameraRegion),
          hasPendingFocus: appState.navigation.pendingMapFocus != nil
        )
      }
      .onChange(of: mapFilterRaw) { _, _ in
        clearSelection()
        selectedContactForDetail = nil
        selectedDiscoveredForDetail = nil
        viewModel.scheduleFilterChange(mapFilter)
      }
      .onChange(of: appState.contactsVersion) { _, _ in
        // contactsVersion also bumps after backup import; re-migrate while the tab is mounted.
        MapFilterPreferences.ensureMigrated(raw: &mapFilterRaw, host: .mainMap)
        viewModel.scheduleCoalescedReload(filter: mapFilter)
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        configureViewModel()
        viewModel.scheduleCoalescedReload(filter: mapFilter)
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
                filter: mapFilter,
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
          filter: mapFilter,
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
