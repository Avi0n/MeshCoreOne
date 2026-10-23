import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Chat send admission", .serialized)
@MainActor
struct ChatSendAdmissionTests {
  private let timeout: Duration = .seconds(3)

  @Test(arguments: [false, true])
  func `service loss before message creation surfaces a send failure`(isChannel: Bool) async {
    let viewModel = ChatViewModel()
    let radioID = UUID()
    viewModel.configureForTesting(dependencies: .testDefaults())

    selectConversation(viewModel, radioID: radioID, isChannel: isChannel)
    let reachedService = await submit("Retain this text", to: viewModel, isChannel: isChannel)

    #expect(!reachedService)
    #expect(viewModel.messages.isEmpty)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
  }

  @Test(arguments: [false, true])
  func `queue rejection retains one failed Message without restoring a duplicate Draft`(isChannel: Bool) async throws {
    let radioID = UUID()
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let service = MessageService(session: MeshCoreSession(transport: MockTransport()), dataStore: store, contactService: nil)
    let viewModel = ChatViewModel()
    viewModel.configureForTesting(dependencies: .testDefaults(dataStore: { store }, messageService: { service }))
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())
    selectConversation(viewModel, radioID: radioID, isChannel: isChannel)
    if let contact = viewModel.currentContact { try await store.saveContact(contact) }

    let reachedService = await submit("Keep one failed Message", to: viewModel, isChannel: isChannel)

    #expect(reachedService)
    let message = try #require(viewModel.messages.first)
    #expect(viewModel.messages.count == 1)
    #expect(message.status == .failed)
    #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
    #expect(try await store.fetchPendingSends(radioID: radioID).isEmpty)
    #expect(viewModel.sendErrorMessage == L10n.Chats.Chats.Alert.UnableToSend.message)
  }

  @Test(arguments: [false, true])
  func `production tabs retain the draft when service loss wins the send handoff`(isChannel: Bool) async throws {
    let suiteName = "test.send-admission.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let appState = AppState(defaults: defaults)
    let radioID = UUID()
    let contact = ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize),
      name: "Test", typeRawValue: ContactType.chat.rawValue,
      lastHeardTimestamp: 0
    ))
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: 1, name: "Test",
      secret: Data(repeating: 1, count: ProtocolLimits.channelSecretSize),
      isEnabled: true, lastMessageDate: nil, unreadCount: 0
    )
    let conversationID: ChatConversationID = isChannel
      ? .channel(radioID: radioID, channelIndex: channel.index)
      : .dm(radioID: radioID, contactID: contact.id)
    appState.connectionManager.setTestState(connectionState: .ready)
    appState.navigation.setChatsRoute(isChannel ? .channel(channel) : .direct(contact))

    let controller = UIHostingController(rootView: MainTabView().environment(\.appState, appState))
    let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
    let window = UIWindow(windowScene: scene)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.rootViewController = nil
      window.isHidden = true
      window.windowScene = nil
      appState.shutdown()
      defaults.removePersistentDomain(forName: suiteName)
    }

    try await waitUntil(timeout: timeout, "production composer should appear") {
      composer(in: window) != nil
    }
    let field = try #require(composer(in: window))
    let text = "Retain this\nmultiline Draft"
    field.insertText(text)
    let editor = appState.chatDraftEditor(for: conversationID)
    try await waitUntil(timeout: timeout, "native input should reach the Draft") { editor.text == text }

    #expect(field.onSend?() == true)
    #expect(editor.text.isEmpty)
    try await waitUntil(timeout: timeout, "rejected send should restore the Draft") { editor.text == text }
    editor.flush()
    #expect(DraftStore(defaults: defaults).draft(for: conversationID) == text)
    #expect(appState.navigation.chatsSelectedRoute?.conversationID == (isChannel ? channel.id : contact.id))
  }

  private func composer(in view: UIView) -> ChatComposerUITextView? {
    if let field = view as? ChatComposerUITextView { return field }
    for child in view.subviews {
      if let field = composer(in: child) { return field }
    }
    return nil
  }

  private func selectConversation(_ viewModel: ChatViewModel, radioID: UUID, isChannel: Bool) {
    if isChannel {
      viewModel.currentChannel = ChannelDTO(
        id: UUID(), radioID: radioID, index: 1, name: "Test",
        secret: Data(repeating: 1, count: ProtocolLimits.channelSecretSize),
        isEnabled: true, lastMessageDate: nil, unreadCount: 0
      )
    } else {
      viewModel.currentContact = ContactDTO(from: Contact(
        radioID: radioID,
        publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize),
        name: "Test", typeRawValue: ContactType.chat.rawValue,
        lastHeardTimestamp: 0
      ))
    }
  }

  private func submit(_ text: String, to viewModel: ChatViewModel, isChannel: Bool) async -> Bool {
    if isChannel {
      await viewModel.sendChannelMessage(text: text)
    } else {
      await viewModel.sendMessage(text: text)
    }
  }
}
