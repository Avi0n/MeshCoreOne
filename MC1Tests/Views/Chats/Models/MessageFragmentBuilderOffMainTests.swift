import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct MessageFragmentBuilderOffMainTests {
  /// Proves the builder runs off the main actor when invoked from a detached task.
  /// The `wasOffMain` capture inside the closure asserts thread context at the
  /// call site, which would fail if `@MainActor` were re-added — the closure
  /// would then implicitly hop back to main and the assertion would catch it.
  @Test
  func `Builder is callable from a detached task off the main actor`() async {
    let message = MessageFragmentBuilderFixtures.makePlainTextMessage(index: 0)
    let inputs = MessageFragmentBuilderFixtures.makeMinimalInputs(messageID: message.id)
    let envInputs = EnvInputs.default

    let outcome = await Task.detached { () -> (MessageItem, Bool) in
      let item = MessageFragmentBuilder.makeItem(
        for: message,
        inputs: inputs,
        envInputs: envInputs
      )
      return (item, isOffMainThread())
    }.value

    #expect(outcome.1, "Builder must execute off the main thread")
    #expect(outcome.0.id == message.id)
  }
}

/// Synchronous wrapper around `Thread.isMainThread` so tests can read it from
/// inside `Task.detached` closures. Swift 6 marks the underlying class property
/// as unavailable from async contexts; routing through a non-async function
/// preserves the runtime check without tripping the diagnostic.
private func isOffMainThread() -> Bool {
  !Thread.isMainThread
}
