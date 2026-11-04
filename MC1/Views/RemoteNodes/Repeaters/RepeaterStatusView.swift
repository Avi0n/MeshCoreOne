import MC1Services
import SwiftUI

/// Guest standalone sheet for repeater stats, telemetry, and neighbors.
struct RepeaterStatusView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  let session: RemoteNodeSessionDTO
  @State private var viewModel = RepeaterStatusViewModel()
  @State private var contacts: [ContactDTO] = []
  @State private var discoveredNodes: [DiscoveredNodeDTO] = []
  /// The node's contact, kept live so the route section reflects the path the firmware learns after
  /// a flood login (delivered asynchronously as a contact update).
  @State private var routeContact: ContactDTO?

  var body: some View {
    NavigationStack {
      RepeaterStatusContent(
        viewModel: viewModel,
        session: session,
        connectionState: appState.connectionState,
        contacts: contacts,
        discoveredNodes: discoveredNodes,
        userLocation: appState.bestAvailableLocation,
        connectedDeviceID: appState.connectionSnapshot(for: session.radioID)?.device.radioID,
        routePathContact: routeContact
      )
      .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
        }

        ToolbarItemGroup(placement: .keyboard) {
          Spacer()
          Button(L10n.RemoteNodes.RemoteNodes.done) {
            UIApplication.shared.sendAction(
              #selector(UIResponder.resignFirstResponder),
              to: nil,
              from: nil,
              for: nil
            )
          }
        }
      }
      .task(id: appState.servicesVersion) {
        guard let source = appState.connectionSnapshot(for: session.radioID) else { return }
        let context = ConnectionCallbackContext(source)
        viewModel = appState.remoteAdminWorkspaces.repeater(for: session).status
        viewModel.configure(
          repeaterAdminService: { appState.connectionSnapshot(for: session.radioID)?.services.repeaterAdminService },
          contactService: { appState.connectionSnapshot(for: session.radioID)?.services.contactService },
          nodeSnapshotService: { appState.connectionSnapshot(for: session.radioID)?.services.nodeSnapshotService },
          deviceHashSize: { appState.connectionSnapshot(for: session.radioID)?.device.hashSize }
        )
        await viewModel.registerHandlers()
        guard !Task.isCancelled, context.isCurrent(appState.connectionManager),
              source.services.isAcceptingRuntimeWork else { return }

        // Pre-load OCV settings and contacts for neighbor matching
        await viewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: session.radioID)
        let loadedContacts = await (try? source.services.dataStore.fetchContacts(radioID: session.radioID)) ?? []
        let loadedDiscovered = await (try? source.services.dataStore.fetchDiscoveredNodes(radioID: session.radioID)) ?? []
        guard !Task.isCancelled, context.isCurrent(appState.connectionManager),
              source.services.isAcceptingRuntimeWork else { return }
        contacts = loadedContacts
        discoveredNodes = loadedDiscovered
        await refreshRouteContact()
      }
      .onChange(of: appState.contactsVersion) {
        Task { await refreshRouteContact() }
      }
    }
    .onDisappear {
      viewModel.pauseDiscoveryPolls()
    }
    .presentationDetents([.large])
  }

  private func refreshRouteContact() async {
    guard let source = appState.connectionSnapshot(for: session.radioID) else { return }
    let context = ConnectionCallbackContext(source)
    if let updated = await (try? source.services.dataStore.fetchContact(
      radioID: session.radioID,
      publicKey: session.publicKey
    )).flatMap(\.self) {
      guard !Task.isCancelled, context.isCurrent(appState.connectionManager),
            source.services.isAcceptingRuntimeWork else { return }
      routeContact = updated
    }
  }
}

#Preview {
  RepeaterStatusView(
    session: RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Repeater",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
  )
  .environment(\.appState, AppState())
}
