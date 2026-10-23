import MC1Services
import SwiftUI

/// CLI terminal for a chat contact's radio. The contact's radio runs a command
/// only when its owner granted this radio Remote CLI.
struct RemoteCLISheet: View {
  @Environment(\.appState) private var appState

  let contact: ContactDTO
  @State private var viewModel = NodeCLIViewModel()

  var body: some View {
    NodeCLIView(viewModel: viewModel)
      .navigationTitle(L10n.Contacts.Contacts.Detail.remoteCLI)
      .navigationBarTitleDisplayMode(.inline)
      .task {
        let publicKey = contact.publicKey
        let sessionName = contact.displayName
        viewModel.configure(
          sessionName: sessionName,
          remoteTarget: .radio,
          sendRawCommand: { command, timeout in
            // Read the live connection. A nil service means the radio disconnected.
            guard let service = appState.services?.remoteNodeService else {
              throw RemoteNodeError.notConnected
            }
            return try await service.sendRadioCLICommand(
              to: publicKey,
              command: command,
              timeout: timeout
            )
          }
        )
      }
      // `NodeCLIView` does not cancel, so repeater and room CLI keep a command when their screen leaves.
      .onDisappear { viewModel.cancelCurrentCommand() }
  }
}
