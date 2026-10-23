import MC1Services
import SwiftUI

struct DiscoveryPresenter: ViewModifier {
  private struct LocationSample: Equatable {
    let latitude: Double
    let longitude: Double
  }

  @Environment(\.appState) private var appState
  @AppStorage(AppStorageKey.discoverySortOrder.rawValue) private var sortOrder: NodeSortOrder = .lastHeard
  let state: DiscoveryState?

  private var locationSample: LocationSample? {
    guard let location = appState.bestAvailableLocation else { return nil }
    return LocationSample(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
  }

  func body(content: Content) -> some View {
    content
      .task(id: state?.id) {
        state?.configure(appState: appState)
        state?.updateSortOrder(sortOrder)
        state?.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        state?.configure(appState: appState)
        state?.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.contactsVersion) { _, _ in
        state?.reloadIfNeeded(appState: appState)
      }
      .onChange(of: sortOrder) { _, order in
        state?.updateSortOrder(order)
      }
      .onChange(of: locationSample) { _, _ in
        if state?.consumesLocation == true { state?.refreshVisibleNodes() }
      }
      .onChange(of: state?.viewModel.visibleNodes.map(\.id)) { _, ids in
        if let state, let scrollPositionID = state.scrollPositionID,
           let ids, !ids.contains(scrollPositionID) {
          state.scrollPositionID = ids.first
        }
      }
      .errorAlert(Binding(
        get: { state?.viewModel.errorMessage },
        set: { state?.viewModel.errorMessage = $0 }
      ), title: L10n.Contacts.Contacts.Common.error)
      .confirmationDialog(
        L10n.Contacts.Contacts.Discovery.Clear.title,
        isPresented: Binding(
          get: { state?.showClearConfirmation ?? false },
          set: { state?.showClearConfirmation = $0 }
        ),
        titleVisibility: .visible
      ) {
        Button(L10n.Contacts.Contacts.Discovery.Clear.confirm, role: .destructive) {
          state?.clearAll()
        }
      } message: {
        Text(L10n.Contacts.Contacts.Discovery.Clear.message)
      }
  }
}
