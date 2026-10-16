import Foundation
@testable import MC1Services

/// Mock implementation of AppStateProvider for testing.
/// Uses actor for thread-safe mutable state access.
public actor MockAppStateProvider: AppStateProvider {
  // MARK: - Stubs

  /// Configurable foreground state for tests
  public var stubbedIsInForeground: Bool
  private var shouldHangForegroundChecks = false
  private var foregroundContinuation: CheckedContinuation<Void, Never>?
  public private(set) var isWaitingOnForegroundCheck = false

  // MARK: - Protocol Properties

  public var isInForeground: Bool {
    get async {
      if shouldHangForegroundChecks {
        isWaitingOnForegroundCheck = true
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
          if shouldHangForegroundChecks {
            foregroundContinuation = continuation
          } else {
            continuation.resume()
          }
        }
        isWaitingOnForegroundCheck = false
      }
      return stubbedIsInForeground
    }
  }

  // MARK: - Initialization

  public init(isInForeground: Bool = true) {
    stubbedIsInForeground = isInForeground
  }

  // MARK: - Test Helpers

  /// Sets the stubbed foreground state
  public func setIsInForeground(_ value: Bool) {
    stubbedIsInForeground = value
  }

  /// Parks the next `isInForeground` read until `releaseForegroundCheck()`.
  public func hangForegroundChecks() {
    shouldHangForegroundChecks = true
  }

  public func releaseForegroundCheck() {
    shouldHangForegroundChecks = false
    let continuation = foregroundContinuation
    foregroundContinuation = nil
    continuation?.resume()
  }
}
