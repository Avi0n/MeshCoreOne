import MC1Services
import SwiftUI

/// Owns standalone contact state; ContactsListView supplies its own retained state.
struct ContactDetailView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss
  @Environment(\.isPresented) private var isPresented
  @State private var state: ContactDetailState
  @State private var isVisible = false

  init(contact: ContactDTO, showFromDirectChat: Bool = false, onClearMessages: @escaping () -> Void = {}) {
    _state = State(initialValue: ContactDetailState(
      contact: contact,
      showFromDirectChat: showFromDirectChat,
      onClearMessages: onClearMessages
    ))
  }

  var body: some View {
    ContactDetailContent(state: state)
      .modifier(ContactDetailPresenter(state: state))
      .toolbar {
        if isPresented {
          ToolbarItem(placement: .confirmationAction) {
            Button(L10n.Localizable.Common.done) {
              state.end()
              dismiss()
            }
          }
        }
      }
      .navigationDestination(for: ContactRoute.TelemetryHistory.self) { route in
        TelemetryHistoryOverviewView(
          publicKey: route.publicKey,
          radioID: route.radioID,
          showNeighbors: route.showNeighbors
        )
      }
      .onAppear {
        isVisible = true
        state.configure(appState: appState, onClose: { dismiss() })
        state.setActive(true, appState: appState)
      }
      .onChange(of: appState.servicesVersion) {
        state.configure(appState: appState, onClose: { dismiss() })
        state.setActive(isVisible, appState: appState)
      }
      .onDisappear {
        isVisible = false
        state.invalidateRenderer()
        state.setActive(false, appState: appState)
      }
  }
}

#Preview("Default") {
  NavigationStack {
    ContactDetailView(contact: ContactDTO(from: Contact(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Alice",
      latitude: 37.7749,
      longitude: -122.4194,
      lastHeardTimestamp: 0,
      isFavorite: true
    )))
  }
  .environment(\.appState, AppState())
}

#Preview("From Direct Chat") {
  NavigationStack {
    ContactDetailView(
      contact: ContactDTO(from: Contact(
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Alice",
        latitude: 37.7749,
        longitude: -122.4194,
        lastHeardTimestamp: 0,
        isFavorite: true
      )),
      showFromDirectChat: true
    )
  }
  .environment(\.appState, AppState())
}
