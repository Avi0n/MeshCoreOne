import SwiftUI

struct BlockedContactsPresenter: ViewModifier {
  @Environment(\.appState) private var appState
  let state: BlockedContactsState?

  func body(content: Content) -> some View {
    content
      .task(id: state?.id) {
        state?.configure(appState: appState)
        state?.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.servicesVersion) { _, _ in
        state?.configure(appState: appState)
        state?.reloadIfNeeded(appState: appState)
      }
      .onChange(of: appState.contactsVersion) { _, _ in
        state?.reloadIfNeeded(appState: appState)
      }
      .errorAlert(Binding(
        get: { state?.errorMessage },
        set: { state?.errorMessage = $0 }
      ))
  }
}
