@testable import MC1
import Testing

/// Binds a fresh `IncomingAvatarJPEGStore` box for each test so replace-all
/// cannot wipe another suite's map across an `await`.
struct IsolatedIncomingAvatarJPEGStoreTrait: SuiteTrait, TestTrait, TestScoping {
  var isRecursive: Bool {
    true
  }

  func provideScope(
    for test: Test,
    testCase: Test.Case?,
    performing function: () async throws -> Void
  ) async throws {
    let storage = await MainActor.run { IncomingAvatarJPEGStore.Storage() }
    try await IncomingAvatarJPEGStore.$isolatedStorage.withValue(storage) {
      try await function()
    }
  }
}

extension SuiteTrait where Self == IsolatedIncomingAvatarJPEGStoreTrait {
  static var isolatedIncomingAvatarJPEGStore: Self {
    Self()
  }
}
