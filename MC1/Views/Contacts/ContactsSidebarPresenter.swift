import MC1Services
import SwiftUI

struct ContactsSidebarPresenter: ViewModifier {
  @Environment(\.appState) private var appState
  @Bindable var state: ContactsSidebarState
  @AppStorage(AppStorageKey.nodesSortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard

  func body(content: Content) -> some View {
    content
      .task {
        state.configure(appState: appState)
        state.reloadIfNeeded(appState: appState)
        state.updateLocation(for: sortOrder, appState: appState)
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        state.configure(appState: appState)
        state.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.contactsVersion) { _, _ in
        state.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.currentRadioID) { _, _ in
        state.reloadIfNeeded(appState: appState)
      }
      .onChange(of: sortOrder) { _, _ in
        state.updateLocation(for: sortOrder, appState: appState)
      }
      .onChange(of: state.viewModel.hasLoadedOnce) { _, loaded in
        if loaded { state.firstLoadCompleted() }
      }
      .onChange(of: appState.navigation.selectedContact) { _, _ in
        state.admitSelection(appState: appState)
      }
      .onChange(of: state.viewModel.pendingRemovalIDs) { _, _ in
        state.clearRemovedSelection(appState: appState)
      }
      .onChange(of: appState.locationService.authorizationStatus) { _, _ in
        state.updateLocation(for: sortOrder, appState: appState)
      }
      .sheet(isPresented: $state.showShareMyContact) {
        if let device = appState.connectedDevice {
          ContactQRShareSheet(
            contactName: device.nodeName,
            publicKey: device.publicKey,
            contactType: .chat
          )
        }
      }
      .sheet(isPresented: $state.showAddContact) {
        AddContactSheet()
      }
      .alert(L10n.Contacts.Contacts.List.locationUnavailable, isPresented: $state.showLocationDeniedAlert) {
        Button(L10n.Contacts.Contacts.List.openSettings) {
          if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
          }
        }
        Button(L10n.Contacts.Contacts.Common.cancel, role: .cancel) {}
      } message: {
        Text(L10n.Contacts.Contacts.List.distanceRequiresLocation)
      }
      .alert(L10n.Contacts.Contacts.List.cannotRefresh, isPresented: $state.showOfflineRefreshAlert) {
        Button(L10n.Contacts.Contacts.Common.ok, role: .cancel) {}
      } message: {
        Text(L10n.Contacts.Contacts.List.connectToSync)
      }
      .errorAlert(Binding(
        get: { state.viewModel.errorMessage },
        set: { state.viewModel.errorMessage = $0 }
      ), title: L10n.Contacts.Contacts.Common.error)
      .sensoryFeedback(.success, trigger: state.syncSuccessTrigger)
  }
}
