import SwiftUI
import MC1Services

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
            contact = try? await appState.services?.dataStore.fetchContact(
                radioID: session.radioID,
                publicKey: session.publicKey
            )
            isLoading = false
        }
    }
}
