import Foundation
@testable import MC1

/// Test translator that parks inside `translate` until `resume` is called.
@MainActor
final class GatedMessageTranslator: MessageTranslating {
  private var waitForEnter: CheckedContinuation<Void, Never>?
  private var gate: CheckedContinuation<String, Error>?
  private var didEnter = false
  private(set) var translateCount = 0

  func translate(_: String) async throws -> String {
    translateCount += 1
    return try await withCheckedThrowingContinuation { continuation in
      gate = continuation
      didEnter = true
      if let waitForEnter {
        waitForEnter.resume()
        self.waitForEnter = nil
      }
    }
  }

  func waitUntilEntered() async {
    if didEnter { return }
    await withCheckedContinuation { continuation in
      waitForEnter = continuation
    }
  }

  func resume(returning value: String) {
    gate?.resume(returning: value)
    gate = nil
  }

  func resume(throwing error: Error) {
    gate?.resume(throwing: error)
    gate = nil
  }
}
