import MC1Services
import SwiftUI

/// Guest standalone sheet for room server stats, telemetry, and battery curve.
struct RoomStatusView: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  let session: RemoteNodeSessionDTO
  @State private var viewModel = RoomStatusViewModel()

  var body: some View {
    NavigationStack {
      RoomStatusContent(
        viewModel: viewModel,
        session: session,
        connectionState: appState.connectionState,
        connectedDeviceID: appState.connectionSnapshot(for: session.radioID)?.device.radioID
      )
      .navigationTitle(L10n.RemoteNodes.RemoteNodes.RoomStatus.title)
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
        viewModel = appState.remoteAdminWorkspaces.room(for: session).status
        viewModel.configure(
          roomAdminService: { appState.connectionSnapshot(for: session.radioID)?.services.roomAdminService },
          contactService: { appState.connectionSnapshot(for: session.radioID)?.services.contactService },
          nodeSnapshotService: { appState.connectionSnapshot(for: session.radioID)?.services.nodeSnapshotService }
        )
        await viewModel.registerHandlers()
        guard !Task.isCancelled, context.isCurrent(appState.connectionManager),
              source.services.isAcceptingRuntimeWork else { return }

        // Pre-load OCV settings
        if let radioID = appState.connectionSnapshot(for: session.radioID)?.device.radioID {
          await viewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: radioID)
        }
      }
    }
    .presentationDetents([.large])
  }
}

#Preview {
  RoomStatusView(
    session: RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Room",
      role: .roomServer,
      isConnected: true,
      permissionLevel: .admin
    )
  )
  .environment(\.appState, AppState())
}
