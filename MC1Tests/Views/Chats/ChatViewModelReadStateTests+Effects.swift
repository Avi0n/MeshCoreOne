import Foundation
@testable import MC1
@testable import MC1Services
import Testing
import UserNotifications

extension ChatViewModelReadStateTests {
  @Test(arguments: [false, true], [false, true])
  func `offline DM and channel opens publish residual or zero badge counts`(isChannel: Bool, hasResidual: Bool) async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let viewModel = environment.viewModel
    for conversation in [Conversation.direct(environment.contact), .channel(environment.channel), .room(environment.room)] {
      await viewModel.setReadState(conversation, isRead: true)
    }
    let conversation: Conversation = isChannel ? .channel(environment.channel) : .direct(environment.contact)
    await viewModel.setReadState(conversation, isRead: false)
    if hasResidual { await viewModel.setReadState(.room(environment.room), isRead: false) }
    #expect(environment.recorder.badges.last == (hasResidual ? 2 : 1))
    viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())

    if isChannel {
      await viewModel.loadChannelMessages(for: environment.channel, populateMode: .replace)
    } else {
      await viewModel.loadMessages(for: environment.contact, populateMode: .replace)
    }

    #expect(environment.recorder.badges.last == (hasResidual ? 1 : 0))
    #expect(try await (isChannel ? Kind.channel : .direct).unreadCount(in: environment) == 0)
    #expect(viewModel.errorMessage == nil)
  }

  @Test(arguments: Kind.allCases, [false, true])
  func `failed saves have no notification or sync effects`(kind: Kind, isRead: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let conversation = kind.conversation(in: environment)
    environment.recorder.requests = [kind.request(identifier: "matching", in: environment)]
    try await kind.delete(in: environment)

    await environment.viewModel.setReadState(conversation, isRead: isRead)

    #expect(environment.viewModel.errorMessage == L10n.Chats.Chats.Error.readStateSaveFailed)
    #expect(environment.viewModel.liveConversation(id: conversation.id)?.unreadCount == 4)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.loads == (isRead ? 1 : 0))
    #expect(environment.sync.conversationsVersion == 0)
  }

  @Test
  func `read removes its snapshot and preserves a later delivery`() async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let arrived = Gate()
    let resume = Gate()
    let content = UNMutableNotificationContent()
    content.userInfo = ["contactID": environment.contact.id.uuidString]
    let original = UNNotificationRequest(identifier: "old", content: content, trigger: nil)
    let unrelated = UNNotificationRequest(identifier: "unrelated", content: UNMutableNotificationContent(), trigger: nil)
    environment.recorder.requests = [original, unrelated]
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    environment.recorder.requests.append(UNNotificationRequest(identifier: "late", content: content, trigger: nil))
    await resume.open()
    await operation.value

    #expect(environment.recorder.removed == ["old"])
    #expect(environment.recorder.requests.map(\.identifier) == ["unrelated", "late"])
  }

  @Test
  func `failed save after radio replacement cannot show an old error`() async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let owner = Owner(environment)
    owner.notificationService = nil
    owner.configure(environment.viewModel)
    let arrived = Gate()
    let resume = Gate()
    environment.recorder.snapshotHook = {
      await arrived.open()
      await resume.wait()
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    try await environment.store.deleteContact(id: environment.contact.id)
    owner.radioID = UUID()
    await resume.open()
    await operation.value

    #expect(environment.viewModel.errorMessage == nil)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.sync.conversationsVersion == 0)
  }

  @Test(arguments: [false, true])
  func `connected badge fetch loses ownership after radio or service replacement`(replaceService: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let service = try #require(environment.viewModel.notificationService)
    let owner = Owner(environment)
    owner.configure(environment.viewModel)
    let arrived = Gate()
    let resume = Gate()
    let store = environment.store
    let capturedRadioID = environment.radioID
    service.getBadgeCount = {
      let counts = await (try? store.getTotalUnreadCounts(radioID: capturedRadioID)) ?? (contacts: 0, channels: 0, rooms: 0)
      await arrived.open()
      await resume.wait()
      return counts
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    if replaceService {
      owner.notificationService = NotificationService(notificationDefaults: environment.defaults, systemOperations: environment.recorder.operations)
    } else {
      owner.radioID = UUID()
    }
    await resume.open()
    await operation.value

    #expect(environment.recorder.badges.isEmpty)
    #expect(service.badgeCount == 0)
  }

  @Test(arguments: [false, true], [0, 1, 2, 3])
  func `prime and unsuccessful opens have no read-state effects`(isChannel: Bool, mode: Int) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let viewModel = environment.viewModel
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)
    if mode == 1 { viewModel.timeline.testPopulateFetchError = PersistenceStoreError.contactNotFound }
    if mode == 2 {
      let service = viewModel.notificationService
      viewModel.configureForTesting(dependencies: .testDefaults(
        notificationService: { service }, currentRadioID: { environment.radioID }
      ))
    }
    if mode == 0 {
      if isChannel {
        #expect(await viewModel.primeInitialChannelMessages(for: environment.channel, populateMode: .replace))
      } else {
        #expect(await viewModel.primeInitialMessages(for: environment.contact, populateMode: .replace))
      }
    } else if mode == 3 {
      let arrived = Gate()
      let resume = Gate()
      coordinator.testPopulateAfterFetchHook = {
        await arrived.open()
        await resume.wait()
      }
      let open = Task {
        if isChannel {
          await viewModel.loadChannelMessages(for: environment.channel, populateMode: .replace)
        } else {
          await viewModel.loadMessages(for: environment.contact, populateMode: .replace)
        }
      }
      await arrived.wait()
      open.cancel()
      await resume.open()
      await open.value
    } else if isChannel {
      await viewModel.loadChannelMessages(for: environment.channel, populateMode: .replace)
    } else {
      await viewModel.loadMessages(for: environment.contact, populateMode: .replace)
    }

    #expect(try await environment.store.fetchContact(id: environment.contact.id)?.unreadCount == 4)
    #expect(try await environment.store.fetchChannel(id: environment.channel.id)?.unreadCount == 4)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.recorder.removed.isEmpty)
    #expect(environment.recorder.loads == 0)
    #expect(environment.sync.conversationsVersion == 0)
  }
}
