@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("Chat list pending navigation")
@MainActor
struct ChatListActionsPendingNavigationTests {
  @Test
  func `pending DM consumption navigates once and clears only that intent`() {
    let appState = AppState()
    let contact = makeContact()
    var navigated: [ChatRoute] = []
    let actions = makeActions(appState: appState) { navigated.append($0) }

    appState.navigation.navigateToChat(with: contact)
    actions.handlePendingNavigation()
    actions.handlePendingNavigation()

    #expect(navigated == [.direct(contact)])
    #expect(appState.navigation.pendingChatContact == nil)
    #expect(appState.navigation.chatsSelectedRoute == .direct(contact))
  }

  @Test
  func `pending channel consumption does not re-apply a DM already selected by the notification`() {
    let appState = AppState()
    let contact = makeContact()
    var navigated: [ChatRoute] = []
    let actions = makeActions(appState: appState) { navigated.append($0) }

    appState.navigation.navigateToChat(with: contact)
    actions.handlePendingChannelNavigation()
    actions.handlePendingRoomNavigation()
    actions.handlePendingNavigation()

    #expect(navigated == [.direct(contact)])
    #expect(appState.navigation.pendingChannel == nil)
    #expect(appState.navigation.pendingRoomSession == nil)
  }

  @Test
  func `pending room authentication is copied into the host without selecting the room`() {
    let appState = AppState()
    let session = makeRoomSession()
    let auth = SessionBox()
    var navigated: [ChatRoute] = []
    let actions = makeActions(
      appState: appState,
      roomToAuthenticate: Binding(get: { auth.session }, set: { auth.session = $0 }),
      navigate: { navigated.append($0) }
    )

    appState.navigation.navigateToRoom(with: session)
    actions.consumePendingRoomAuthentication()

    #expect(auth.session == session)
    #expect(appState.navigation.pendingRoomAuthentication == nil)
    #expect(appState.navigation.chatsSelectedRoute == nil)
    #expect(navigated.isEmpty)
  }

  private func makeActions(
    appState: AppState,
    roomToAuthenticate: Binding<RemoteNodeSessionDTO?> = .constant(nil),
    navigate: @escaping (ChatRoute) -> Void
  ) -> ChatListActions {
    ChatListActions(
      viewModel: ChatViewModel(),
      appState: appState,
      roomToDelete: .constant(nil),
      showRoomDeleteAlert: .constant(false),
      channelDeleteFailure: .constant(nil),
      showChannelDeleteFailed: .constant(false),
      roomToAuthenticate: roomToAuthenticate,
      navigate: navigate,
      clearNavigationIfActive: { _ in }
    )
  }

  private func makeContact() -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xAA, count: ProtocolLimits.publicKeySize),
      name: "Alex",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
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
