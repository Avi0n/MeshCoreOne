import Foundation
@testable import MC1
@testable import MC1Services

extension ChatViewModelReadStateTests {
  @MainActor
  final class Owner {
    var radioID: UUID
    var store: PersistenceStore
    var syncCoordinator: SyncCoordinator?
    var notificationService: NotificationService?

    init(_ environment: Environment) {
      radioID = environment.radioID
      store = environment.store
      syncCoordinator = environment.viewModel.syncCoordinator
      notificationService = environment.viewModel.notificationService
    }

    func configure(_ viewModel: ChatViewModel) {
      viewModel.configureForTesting(dependencies: .testDefaults(
        dataStore: { self.store }, notificationService: { self.notificationService },
        syncCoordinator: { self.syncCoordinator }, currentRadioID: { self.radioID }
      ))
    }
  }
}
