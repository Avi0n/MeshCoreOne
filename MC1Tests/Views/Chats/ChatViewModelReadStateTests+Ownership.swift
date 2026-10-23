import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  @Test(arguments: Kind.allCases, [false, true])
  func `successful save cleans its snapshot without publishing to a replacement owner`(kind: Kind, replaceStore: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
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
    if replaceStore {
      owner.store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    } else {
      owner.radioID = UUID()
    }
    await environment.viewModel.requestConversationReload()?.value
    await resume.open()
    await operation.value

    #expect(try await kind.unreadCount(in: environment) == 0)
    #expect(environment.viewModel.allConversations.isEmpty)
    #expect(environment.recorder.removed == ["matching"])
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 0)
    #expect(environment.viewModel.errorMessage == nil)
  }

  @Test(arguments: Kind.allCases, [false, true])
  func `cancelled preparing command finishes captured work without publishing`(kind: Kind, failSave: Bool) async throws {
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
    let operation = Task { await environment.viewModel.setReadState(conversation, isRead: true) }
    await arrived.wait()
    if failSave { try await kind.delete(in: environment) }
    operation.cancel()
    await resume.open()
    await operation.value

    #expect(try await kind.unreadCount(in: environment) == (failSave ? nil : 0))
    #expect(environment.viewModel.liveConversation(id: conversation.id)?.unreadCount == 4)
    #expect(environment.recorder.removed == (failSave ? [] : ["matching"]))
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 0)
    #expect(environment.viewModel.errorMessage == nil)
  }

  @Test(arguments: [false, true])
  func `cancellation targets its own reload rather than a replacement`(replaceReload: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    environment.viewModel.reloadInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    let nextArrived = Gate()
    let nextResume = Gate()
    let next: Task<Void, Never>?
    if replaceReload {
      environment.viewModel.reloadInterleaveHook = {
        await nextArrived.open()
        await nextResume.wait()
      }
      next = environment.viewModel.requestConversationReload()
      await nextArrived.wait()
    } else {
      next = nil
    }
    operation.cancel()
    await resume.open()
    await operation.value
    await nextResume.open()
    await next?.value

    #expect(environment.viewModel.liveConversation(id: environment.contact.id)?.unreadCount == (replaceReload ? 0 : 4))
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 1)
  }

  @Test
  func `row cancellation reaches a connected badge fetch`() async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let service = try #require(environment.viewModel.notificationService)
    let arrived = Gate()
    let resume = Gate()
    let store = environment.store
    let radioID = environment.radioID
    service.getBadgeCount = {
      let counts = try? await store.getTotalUnreadCounts(radioID: radioID)
      await arrived.open()
      await resume.wait()
      return counts ?? (contacts: 0, channels: 0, rooms: 0)
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    operation.cancel()
    await resume.open()
    await operation.value

    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.viewModel.liveConversation(id: environment.contact.id)?.unreadCount == 0)
    #expect(environment.sync.conversationsVersion == 1)
  }
}
