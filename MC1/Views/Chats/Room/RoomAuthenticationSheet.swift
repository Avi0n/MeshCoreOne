import MC1Services
import SwiftUI

struct RoomAuthenticationSheet: View {
  @Environment(\.appState) private var appState

  let session: RemoteNodeSessionDTO
  let onSuccess: (RemoteNodeSessionDTO) -> Void

  @State private var contact: ContactDTO?
  @State private var isLoading = true

  var body: some View {
    Group {
      if isLoading {
        ProgressView()
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      } else if let contact {
        NodeAuthenticationSheet(
          contact: contact,
          role: .roomServer,
          hideNodeDetails: true,
          onSuccess: onSuccess
        )
      } else {
        ContentUnavailableView(
          L10n.Chats.Chats.RoomAuth.NotFound.title,
          systemImage: "exclamationmark.triangle",
          description: Text(L10n.Chats.Chats.RoomAuth.NotFound.description)
        )
      }
    }
    .task {
      defer { isLoading = false }
      guard let source = appState.connectionSnapshot(for: session.radioID) else { return }
      let context = ConnectionCallbackContext(source)
      let fetched = try? await source.services.dataStore.fetchContact(
        radioID: session.radioID,
        publicKey: session.publicKey
      )
      guard !Task.isCancelled, context.isCurrent(appState.connectionManager),
            source.services.isAcceptingRuntimeWork else { return }
      contact = fetched
    }
  }
}
