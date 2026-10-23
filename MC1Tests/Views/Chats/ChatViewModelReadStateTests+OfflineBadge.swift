import Foundation
@testable import MC1
@testable import MC1Services
import Testing

extension ChatViewModelReadStateTests {
  private enum OfflineBadgeChange: CaseIterable, Sendable {
    case radio, store, service, cancellation
  }

  @Test(arguments: OfflineBadgeChange.allCases)
  private func `offline badge fetch cannot publish after ownership loss or cancellation`(change: OfflineBadgeChange) async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    let owner = Owner(environment)
    owner.configure(environment.viewModel)
    let arrived = Gate()
    let resume = Gate()
    environment.viewModel.readStateBadgeFetchInterleaveHook = {
      await arrived.open()
      await resume.wait()
    }
    let operation = Task { await environment.viewModel.setReadState(.direct(environment.contact), isRead: true) }
    await arrived.wait()
    switch change {
    case .radio: owner.radioID = UUID()
    case .store: owner.store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    case .service:
      owner.notificationService = NotificationService(notificationDefaults: environment.defaults, systemOperations: environment.recorder.operations)
    case .cancellation: operation.cancel()
    }
    await resume.open()
    await operation.value

    #expect(try await environment.store.fetchContact(id: environment.contact.id)?.unreadCount == 0)
    #expect(environment.recorder.badges.isEmpty)
    #expect(environment.viewModel.errorMessage == nil)
  }

  @Test(arguments: [
    AppStorageKey.notificationBadgeEnabled.rawValue, AppStorageKey.notifyContactMessages.rawValue,
    AppStorageKey.notifyChannelMessages.rawValue, AppStorageKey.notifyRoomMessages.rawValue
  ])
  func `offline row badge uses the view model preference suite`(disabledKey: String) async throws {
    let environment = try await makeEnvironment()
    defer { environment.cleanup() }
    environment.defaults.set(false, forKey: disabledKey)

    await environment.viewModel.setReadState(.room(environment.room), isRead: false)

    #expect(environment.recorder.badges == (disabledKey == AppStorageKey.notificationBadgeEnabled.rawValue ? [0] : [8]))
  }
}
