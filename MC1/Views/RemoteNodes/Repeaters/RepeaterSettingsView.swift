import MC1Services
import SwiftUI

struct RepeaterSettingsView: View {
  @Environment(\.appState) private var appState
  let session: RemoteNodeSessionDTO

  var body: some View {
    RepeaterSettingsWorkspace(
      session: session,
      viewModel: appState.remoteAdminWorkspaces.repeaterSettings(for: session),
      statusViewModel: appState.remoteAdminWorkspaces.repeaterStatus(for: session),
      cliViewModel: appState.remoteAdminWorkspaces.nodeCLI(for: session)
    )
  }
}

#Preview("Repeater Settings") {
  NavigationStack {
    RepeaterSettingsView(
      session: RemoteNodeSessionDTO(
        id: UUID(),
        radioID: UUID(),
        publicKey: Data(repeating: 0x42, count: 32),
        name: "Mountain Peak Repeater",
        role: .repeater,
        latitude: 37.7749,
        longitude: -122.4194,
        isConnected: true,
        permissionLevel: .admin
      )
    )
    .environment(\.appState, AppState())
  }
}
