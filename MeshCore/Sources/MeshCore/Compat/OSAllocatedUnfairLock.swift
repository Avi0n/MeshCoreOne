#if !canImport(os)
import Foundation

/// Source-compatible replacement for `os.OSAllocatedUnfairLock`, compiled only
/// where `os` is unavailable. Backed by `NSLock`; exposes just the state-holding
/// surface the session code relies on (`init(initialState:)` and `withLock`).
final class OSAllocatedUnfairLock<State>: @unchecked Sendable {
  private let lock = NSLock()
  private var state: State

  init(initialState: State) { state = initialState }

  func withLock<R>(_ body: (inout State) throws -> R) rethrows -> R {
    lock.lock()
    defer { lock.unlock() }
    return try body(&state)
  }
}
#endif
