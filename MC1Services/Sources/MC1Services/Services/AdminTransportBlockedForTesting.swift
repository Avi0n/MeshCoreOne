#if DEBUG
  import os

  /// Thrown before transport when an observed admin call is set to fail.
  public struct AdminTransportBlockedForTesting: Error {}

  /// Records one admin call, runs an optional stub, then fails when asked.
  /// A stub string is the result and does not throw. A nil stub result falls through to the fail flag.
  final class AdminCallObservation: Sendable {
    private struct State: Sendable {
      var calls: [String] = []
      var shouldFail = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    var calls: [String] {
      state.withLock { $0.calls }
    }

    var shouldFail: Bool {
      get { state.withLock { $0.shouldFail } }
      set { state.withLock { $0.shouldFail = newValue } }
    }

    func observe(
      _ command: String,
      stub: (@Sendable (String) async throws -> String?)? = nil
    ) async throws -> String? {
      state.withLock { $0.calls.append(command) }
      if let stub, let replacement = try await stub(command) {
        return replacement
      }
      if shouldFail {
        throw AdminTransportBlockedForTesting()
      }
      return nil
    }
  }
#endif
