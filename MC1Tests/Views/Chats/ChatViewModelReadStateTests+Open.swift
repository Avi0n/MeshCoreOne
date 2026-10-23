import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  @Test(arguments: [false, true])
  func `cancelled open preserves unread until a foreground reload`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let coordinator = ChatCoordinator.makeForTesting()
    environment.viewModel.bindCoordinatorForTesting(coordinator)
    let arrived = Gate()
    let resume = Gate()
    coordinator.testPopulateAfterFetchHook = {
      await arrived.open()
      await resume.wait()
    }
    let open = Task {
      if isChannel {
        await environment.viewModel.loadChannelMessages(for: environment.channel, populateMode: .refreshWindow)
      } else {
        await environment.viewModel.loadMessages(for: environment.contact, populateMode: .refreshWindow)
      }
    }
    await arrived.wait()
    open.cancel()
    await resume.open()
    await open.value

    let unreadBeforeReload = if isChannel {
      try await environment.store.getChannelUnreadCount(channelID: environment.channel.id)
    } else {
      try await environment.store.getUnreadCount(contactID: environment.contact.id)
    }
    #expect(unreadBeforeReload == 4)
    #expect(environment.sync.conversationsVersion == 0)
    #expect(environment.recorder.badges.isEmpty)

    coordinator.testPopulateAfterFetchHook = nil
    if isChannel {
      await environment.viewModel.loadChannelMessages(for: environment.channel, populateMode: .refreshWindow)
    } else {
      await environment.viewModel.loadMessages(for: environment.contact, populateMode: .refreshWindow)
    }
    let unreadAfterReload = if isChannel {
      try await environment.store.getChannelUnreadCount(channelID: environment.channel.id)
    } else {
      try await environment.store.getUnreadCount(contactID: environment.contact.id)
    }
    #expect(unreadAfterReload == 0)
  }

  @Test(arguments: [false, true])
  func `connected successful opens notify sync and refresh badges`(isChannel: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    environment.viewModel.bindCoordinatorForTesting(ChatCoordinator.makeForTesting())

    if isChannel {
      await environment.viewModel.loadChannelMessages(for: environment.channel, populateMode: .replace)
    } else {
      await environment.viewModel.loadMessages(for: environment.contact, populateMode: .replace)
    }

    #expect(environment.sync.conversationsVersion == 1)
    #expect(environment.recorder.badges == [8])
    #expect(environment.viewModel.errorMessage == nil)
  }

  @Test(arguments: [false, true], [false, true])
  func `successful opens distinguish radio replacement from coordinator replacement`(isChannel: Bool, replaceCoordinator: Bool) async throws {
    let environment = try await makeEnvironment(connected: true)
    defer { environment.cleanup() }
    let owner = Owner(environment)
    owner.configure(environment.viewModel)
    let coordinator = ChatCoordinator.makeForTesting()
    environment.viewModel.bindCoordinatorForTesting(coordinator)
    let arrived = Gate()
    let resume = Gate()
    coordinator.testPopulateAfterFetchHook = {
      await arrived.open()
      await resume.wait()
    }
    let open = Task {
      if isChannel {
        await environment.viewModel.loadChannelMessages(for: environment.channel, populateMode: .replace)
      } else {
        await environment.viewModel.loadMessages(for: environment.contact, populateMode: .replace)
      }
    }
    await arrived.wait()
    if replaceCoordinator {
      owner.syncCoordinator = SyncCoordinator()
    } else {
      owner.radioID = UUID()
    }
    await resume.open()
    await open.value

    #expect(environment.sync.conversationsVersion == 0)
    #expect(owner.syncCoordinator?.conversationsVersion == 0)
    #expect(environment.recorder.badges == (replaceCoordinator ? [8] : []))
    #expect(environment.viewModel.errorMessage == nil)
  }
}
