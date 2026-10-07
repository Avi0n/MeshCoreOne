import MC1Services
import SwiftUI

struct RoomSettingsView: View {
  @Environment(\.appState) private var appState
  let session: RemoteNodeSessionDTO

  var body: some View {
    RoomSettingsWorkspace(
      session: session,
      viewModel: appState.remoteAdminWorkspaces.roomSettings(for: session),
      statusViewModel: appState.remoteAdminWorkspaces.roomStatus(for: session),
      cliViewModel: appState.remoteAdminWorkspaces.nodeCLI(for: session)
    )
  }
}

#Preview {
  NavigationStack {
    RoomSettingsView(
      session: RemoteNodeSessionDTO(
        id: UUID(),
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Community Room",
        role: .roomServer,
        latitude: 37.7749,
        longitude: -122.4194,
        isConnected: true,
        permissionLevel: .admin
      )
    )
    .environment(\.appState, AppState())
  }
}
