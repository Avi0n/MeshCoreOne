import MC1Services
import SwiftUI

/// Chats-list sheets for links, compose, room auth, and deletes. Injected
/// `navigate` keeps the split host and the list column on one route.
struct ChatsConversationSheets: ViewModifier {
  @Environment(\.appState) private var appState

  let viewModel: ChatViewModel

  @Binding var showingNewChat: Bool
  @Binding var showingChannelOptions: Bool
  @Binding var roomToAuthenticate: RemoteNodeSessionDTO?
  @Binding var roomToDelete: RemoteNodeSessionDTO?
  @Binding var showRoomDeleteAlert: Bool
  @Binding var channelDeleteFailure: ChatConversationActions.Failure?
  @Binding var showChannelDeleteFailed: Bool
  @Binding var pendingChatContact: ContactDTO?
  @Binding var pendingChannel: ChannelDTO?

  let navigate: (ChatRoute) -> Void
  let deleteChannelConversation: (ChannelDTO) -> Void
  let deleteRoom: (RemoteNodeSessionDTO) async -> Void

  func body(content: Content) -> some View {
    content
      .environment(\.openURL, OpenURLAction { url in
        ChatLinkRouter.route(url, appState: appState) ? .handled : .systemAction
      })
      .sheet(item: Binding(
        get: { appState.navigation.pendingHashtag },
        set: { appState.navigation.pendingHashtag = $0 }
      )) { request in
        JoinHashtagFromMessageView(channelName: request.id) { channel in
          appState.navigation.clearPendingHashtag()
          if let channel {
            navigate(.channel(channel))
          }
        }
        .presentationDetents([.medium])
      }
      .sheet(item: Binding(
        get: { appState.navigation.pendingContactLink },
        set: { appState.navigation.pendingContactLink = $0 }
      )) { result in
        AddContactConfirmationSheet(contactResult: result) { addedContact in
          appState.navigation.clearPendingContactLink()
          if let addedContact {
            appState.navigation.navigateToContactDetail(addedContact)
          }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationSizing(.page)
      }
      .sheet(item: Binding(
        get: { appState.navigation.pendingChannelLink },
        set: { appState.navigation.pendingChannelLink = $0 }
      )) { result in
        JoinChannelConfirmationSheet(channelResult: result) { newChannel in
          appState.navigation.clearPendingChannelLink()
          if let newChannel {
            navigate(.channel(newChannel))
          }
        }
        .presentationDetents([.medium, .large])
      }
      .sheet(isPresented: $showingNewChat, onDismiss: {
        if let contact = pendingChatContact {
          pendingChatContact = nil
          navigate(.direct(contact))
        }
      }) {
        NewChatView { contact in
          pendingChatContact = contact
          showingNewChat = false
        }
      }
      .sheet(isPresented: $showingChannelOptions, onDismiss: {
        viewModel.requestConversationReload()
        if let channel = pendingChannel {
          pendingChannel = nil
          navigate(.channel(channel))
        }
      }) {
        ChannelOptionsSheet { channel in
          pendingChannel = channel
        }
      }
      .sheet(item: $roomToAuthenticate) { session in
        RoomAuthenticationSheet(session: session) { authenticatedSession in
          roomToAuthenticate = nil
          guard ChatsRadioScopedSheets.shouldKeepRoomAuth(
            sessionRadioID: authenticatedSession.radioID,
            currentRadioID: appState.currentRadioID,
            hasConnectedDevice: appState.connectedDevice != nil
          ) else { return }
          navigate(.room(authenticatedSession))
        }
        .presentationSizing(.page)
      }
      .alert(L10n.Chats.Chats.Alert.LeaveRoom.title, isPresented: $showRoomDeleteAlert) {
        Button(L10n.Chats.Chats.Common.cancel, role: .cancel) {
          roomToDelete = nil
        }
        Button(L10n.Chats.Chats.Alert.LeaveRoom.confirm, role: .destructive) {
          Task {
            if let session = roomToDelete { await deleteRoom(session) }
            roomToDelete = nil
          }
        }
      } message: {
        Text(L10n.Chats.Chats.Alert.LeaveRoom.message)
      }
      .alert(
        L10n.Chats.Chats.ChannelInfo.DeleteFailed.title,
        isPresented: $showChannelDeleteFailed,
        presenting: channelDeleteFailure
      ) { failure in
        Button(L10n.Localizable.Common.tryAgain) {
          deleteChannelConversation(failure.channel)
        }
        Button(L10n.Chats.Chats.Common.ok, role: .cancel) {}
      } message: { failure in
        Text(failure.message)
      }
  }
}
