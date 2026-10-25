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
      models = appState.remoteAdminWorkspaces.configureSensor(
        for: session,
        remoteNodeService: { [appState] in appState.services?.remoteNodeService },
        dataStore: { [appState] in appState.offlineDataStore },
        connectionState: { [appState] in appState.connectionState }
      )
    }
  }
}
