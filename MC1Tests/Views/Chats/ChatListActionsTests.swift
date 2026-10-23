@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("Chat list pending navigation")
@MainActor
struct ChatListActionsPendingNavigationTests {
  @Test
  func `pending room authentication is copied into the host without selecting the room`() {
    let appState = AppState()
    let session = makeRoomSession()
    let auth = SessionBox()
    let actions = makeActions(
      appState: appState,
      roomToAuthenticate: Binding(get: { auth.session }, set: { auth.session = $0 })
    )

    appState.navigation.navigateToRoom(with: session)
    actions.consumePendingRoomAuthentication()

    #expect(auth.session == session)
    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.chatsSelectedRoute == nil)
  }

  private func makeActions(
    appState: AppState,
    roomToAuthenticate: Binding<RemoteNodeSessionDTO?> = .constant(nil)
  ) -> ChatListActions {
    ChatListActions(
      viewModel: ChatViewModel(),
      appState: appState,
      roomToDelete: .constant(nil),
      showRoomDeleteAlert: .constant(false),
      channelDeleteFailure: .constant(nil),
      showChannelDeleteFailed: .constant(false),
      roomToAuthenticate: roomToAuthenticate,
      clearNavigationIfActive: { _ in }
    )
  }

  private func makeRoomSession() -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xBB, count: ProtocolLimits.publicKeySize),
      name: "Room",
      role: .roomServer,
      isConnected: false
    )
  }
}

@MainActor
private final class SessionBox {
  var session: RemoteNodeSessionDTO?
}
