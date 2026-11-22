#if !canImport(os)
  import Foundation

  /// Source-compatible replacement for `os.OSAllocatedUnfairLock`, compiled only
  /// where `os` is unavailable. Backed by `NSLock`. Exposes `init(initialState:)`,
  /// closure `withLock`, and a key-path read.
  final class OSAllocatedUnfairLock<State>: @unchecked Sendable {
    private let lock = NSLock()
    private var state: State

    init(initialState: State) {
      state = initialState
    }

    func withLock<R>(_ body: (inout State) throws -> R) rethrows -> R {
      lock.lock()
      defer { lock.unlock() }
      return try body(&state)
    }

    func withLock<Member>(_ keyPath: KeyPath<State, Member>) -> Member {
      withLock { $0[keyPath: keyPath] }
    }
  }
#endif
