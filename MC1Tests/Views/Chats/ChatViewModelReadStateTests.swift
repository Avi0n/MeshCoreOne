import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatViewModel read state")
@MainActor
struct ChatViewModelReadStateTests {
  @Test
  func `read and unread work offline and refresh the unread filter`() async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let viewModel = environment.viewModel
    let captured = environment.contact
    environment.recorder.requests = Kind.allCases.map { $0.request(identifier: "\($0)", in: environment) }
    #expect(viewModel.notificationService == nil)

    await viewModel.setReadState(.direct(captured), isRead: true)
    await viewModel.setReadState(.channel(environment.channel), isRead: true)
    await viewModel.setReadState(.room(environment.room), isRead: true)

    #expect(viewModel.liveConversation(id: captured.id)?.unreadCount == 0)
    #expect(viewModel.liveConversation(id: environment.channel.id)?.unreadCount == 0)
    #expect(viewModel.liveConversation(id: environment.room.id)?.unreadCount == 0)
    #expect(viewModel.allConversations.filtered(by: .unread, searchText: "").isEmpty)
    #expect(viewModel.errorMessage == nil)
    #expect(environment.recorder.removed == Kind.allCases.map { "\($0)" })
    #expect(environment.recorder.badges == [8, 4, 0])

    // The held DTO is still unread; the menu's explicit intent must win.
    await viewModel.setReadState(.direct(captured), isRead: false)

    #expect(viewModel.liveConversation(id: captured.id)?.unreadCount == 1)
    #expect(viewModel.allConversations.filtered(by: .unread, searchText: "").map(\.id) == [captured.id])
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `missing store surfaces an error without changing the list`() async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let viewModel = environment.viewModel
    viewModel.configureForTesting(dependencies: .testDefaults(currentRadioID: { environment.radioID }))

    await viewModel.setReadState(.direct(environment.contact), isRead: true)

    #expect(viewModel.errorMessage == L10n.Chats.Chats.Error.readStateSaveFailed)
    #expect(viewModel.liveConversation(id: environment.contact.id)?.unreadCount == 4)
  }

  @Test
  func `read cancels a stale reload so unread state cannot reappear`() async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let viewModel = environment.viewModel
    let arrived = Gate()
    let resume = Gate()
    viewModel.reloadInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    let staleReload = viewModel.requestConversationReload()
    await arrived.wait()
    viewModel.reloadInterleaveHook = nil

    await viewModel.setReadState(.direct(environment.contact), isRead: true)
    await resume.open()
    await staleReload?.value

    #expect(viewModel.liveConversation(id: environment.contact.id)?.unreadCount == 0)
    #expect(try await environment.store.fetchContact(id: environment.contact.id)?.unreadCount == 0)
  }

  @Test(arguments: Kind.allCases, [false, true])
  func `connected row saves emit one event and clean up only on read`(kind: Kind, isRead: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    let stream = environment.sync.dataEvents()

    await environment.viewModel.setReadState(conversation, isRead: isRead)
    environment.sync.finishDataEvents()
    var events: [SyncDataEvent] = []
    for await event in stream {
      events.append(event)
    }

    #expect(events.count == 1)
    #expect(environment.sync.conversationsVersion == 1)
    #expect(environment.recorder.removed == (isRead ? ["matching"] : []))
    #expect(environment.recorder.loads == (isRead ? 1 : 0))
    #expect(environment.recorder.badges.last == (isRead ? 8 : 12))
  }
}
