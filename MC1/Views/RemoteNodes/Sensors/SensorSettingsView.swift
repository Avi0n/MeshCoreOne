import MC1Services
import SwiftUI

struct SensorSettingsView: View {
  @Environment(\.appState) private var appState
  let session: RemoteNodeSessionDTO
  @State private var models: RemoteAdminWorkspaces.SensorModels?

  var body: some View {
    Group {
      if let models {
        SensorSettingsContent(viewModel: models.settings, cliViewModel: models.cli)
      } else {
        ProgressView()
      }
    }
    .task(id: appState.servicesVersion) {
      let radioID = session.radioID
      guard appState.connectionSnapshot(for: radioID) != nil else { return }
      models = appState.remoteAdminWorkspaces.configureSensor(
        for: session,
        remoteNodeService: { [weak appState] in appState?.connectionSnapshot(for: radioID)?.services.remoteNodeService },
        dataStore: { [weak appState] in appState?.offlineDataStore },
        connectionState: { [weak appState] in appState?.connectionState ?? .disconnected }
      )
    }
  }
}
