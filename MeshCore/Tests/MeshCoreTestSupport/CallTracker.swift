import Foundation

/// Thread-safe tracker for verifying async callbacks in tests.
///
/// Use `markCalled()` inside callback closures, then assert on `wasCalled`
/// or `callCount` to verify the callback was (or wasn't) invoked.
///
/// Usage:
/// ```swift
/// let tracker = CallTracker()
/// sut.onComplete = { tracker.markCalled() }
/// await sut.run()
/// #expect(tracker.wasCalled)
/// ```
public final class CallTracker: @unchecked Sendable {
  private var _callCount = 0
  private let lock = NSLock()

  public init() {}

  /// Whether `markCalled()` has been invoked at least once.
  public var wasCalled: Bool {
    lock.lock()
    defer { lock.unlock() }
    return _callCount > 0
  }

  /// Total number of times `markCalled()` was invoked.
  public var callCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return _callCount
  }

  /// Records one invocation.
  public func markCalled() {
    lock.lock()
    defer { lock.unlock() }
    _callCount += 1
  }
}
