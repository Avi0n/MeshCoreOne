import Foundation

final class IsolatedUserDefaults {
  let defaults: UserDefaults
  private let suiteName: String

  init() {
    suiteName = "test.\(UUID().uuidString)"
    defaults = UserDefaults(suiteName: suiteName)!
  }

  deinit {
    defaults.removePersistentDomain(forName: suiteName)
  }
}
