import Foundation
@testable import MC1Services

/// Mock implementation of AppStateProvider for testing.
/// Uses actor for thread-safe mutable state access.
public actor MockAppStateProvider: AppStateProvider {
  // MARK: - Stubs

  /// Configurable foreground state for tests
  public var stubbedIsInForeground: Bool

  // MARK: - Protocol Properties

  public var isInForeground: Bool {
    get async { stubbedIsInForeground }
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
}
