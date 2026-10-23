import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  @Test(arguments: Kind.allCases, [false, true])
  func `newer unread supersedes a read still preparing its snapshot`(kind: Kind, secondWindow: Bool) async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let target = secondWindow ? environment.makeViewModel() : environment.viewModel
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()

    // Stored unread counts can change while notification lookup is suspended.
    try await kind.setReadState(true, in: environment)
    await target.requestConversationReload()?.value
    await target.setReadState(conversation, isRead: false)
    await resume.open()
    await old.value

    #expect(try await kind.unreadCount(in: environment) == 1)
    #expect(target.liveConversation(id: conversation.id)?.unreadCount == 1)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.badges.last == 9)
  }

  @Test(arguments: Kind.allCases, [false, true])
  func `same radio saves refresh after coordinator disconnect or replacement`(kind: Kind, reconnect: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let service = try #require(environment.viewModel.notificationService)
    let owner = Owner(environment)
    owner.configure(environment.viewModel)
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let operation = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()
    owner.syncCoordinator = reconnect ? SyncCoordinator() : nil
    owner.notificationService = reconnect ? service : nil
    await environment.viewModel.requestConversationReload()?.value
    #expect(environment.viewModel.liveConversation(id: conversation.id)?.unreadCount == 4)
    await resume.open()
    await operation.value

    #expect(try await kind.unreadCount(in: environment) == 0)
    #expect(environment.viewModel.liveConversation(id: conversation.id)?.unreadCount == 0)
    #expect(!environment.viewModel.allConversations.filtered(by: .unread, searchText: "").contains { $0.id == conversation.id })
    #expect(environment.recorder.badges.last == 8)
    #expect(environment.recorder.removed == ["matching"])
    #expect(environment.sync.conversationsVersion == 0)
    if reconnect { #expect(owner.syncCoordinator?.conversationsVersion == 1) }
  }

  @Test(arguments: Kind.allCases, [false, true])
  func `overlapping reads admit only the latest snapshot across view models`(kind: Kind, oldFinishesFirst: Bool) async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let target = environment.makeViewModel()
    await target.requestConversationReload()?.value
    let firstArrived = Gate()
    let firstResume = Gate()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    environment.recorder.snapshotHook = {
      await firstArrived.open()
      await firstResume.wait()
    }
    let first = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await firstArrived.wait()
    let lastArrived = Gate()
    let lastResume = Gate()
    environment.recorder.snapshotHook = {
      await lastArrived.open()
      await lastResume.wait()
    }
    let last = Task { await target.setReadState(conversation, isRead: true) }
    await lastArrived.wait()
    if oldFinishesFirst {
      await firstResume.open()
      await first.value
      #expect(environment.recorder.removed.isEmpty)
      await lastResume.open()
      await last.value
    } else {
      await lastResume.open()
      await last.value
      await firstResume.open()
      await first.value
    }

    #expect(try await kind.unreadCount(in: environment) == 0)
    #expect(target.liveConversation(id: conversation.id)?.unreadCount == 0)
    #expect(environment.recorder.removed == ["matching"])
    #expect(environment.recorder.badges == [8])
  }

  @Test(arguments: Kind.allCases)
  func `failed newest command does not revive older preparation`(kind: Kind) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()
    try await kind.delete(in: environment)
    await environment.viewModel.setReadState(conversation, isRead: false)
    await resume.open()
    await old.value

    #expect(environment.viewModel.errorMessage == L10n.Chats.Chats.Error.readStateSaveFailed)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 0)
  }

  @Test(arguments: Kind.allCases)
  func `cancelled newest intent still supersedes older preparation`(kind: Kind) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()
    try await kind.setReadState(true, in: environment)
    let newest = Task { await environment.viewModel.setReadState(conversation, isRead: false) }
    newest.cancel()
    await newest.value
    await resume.open()
    await old.value

    #expect(try await kind.unreadCount(in: environment) == 1)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 0)
  }

  @Test
  func `a different conversation cannot supersede preparing work`() async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.requests = [Kind.direct.request(identifier: "direct", in: environment)]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let old = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    try await Kind.channel.setReadState(true, in: environment)
    await environment.viewModel.setReadState(.channel(environment.channel), isRead: false)
    await resume.open()
    await old.value

    #expect(try await Kind.direct.unreadCount(in: environment) == 0)
    #expect(try await Kind.channel.unreadCount(in: environment) == 1)
    #expect(environment.recorder.removed == ["direct"])
    #expect(environment.recorder.badges == [9, 5])
  }
}
