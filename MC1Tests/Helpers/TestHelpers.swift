import Foundation

/// Thread-safe box for capturing mutable values in async closures during tests.
/// This is needed because Swift's strict concurrency does not allow capturing
/// `var` in async closures that may execute concurrently.
public final class MutableBox<T>: @unchecked Sendable {
  public var value: T

  public init(_ value: T) {
    self.value = value
  }
}
