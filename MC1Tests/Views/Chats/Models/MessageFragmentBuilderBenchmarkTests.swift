import Foundation
@testable import MC1
@testable import MC1Services
import OSLog
import Testing

@MainActor
struct MessageFragmentBuilderBenchmarkTests {
  /// Build 1,000 message items on the main actor and report elapsed time.
  /// Not a pass/fail gate — informational baseline for the off-main migration.
  /// Logger output surfaces in Console.app and Xcode test results;
  /// `print()` would be swallowed by Swift Testing's per-test capture.
  @Test
  func `Baseline: build 1000 items on main actor`() {
    let messages = (0..<1000).map { i in MessageFragmentBuilderFixtures.makePlainTextMessage(index: i) }
    let envInputs = EnvInputs.default
    let inputs = messages.map { MessageFragmentBuilderFixtures.makeMinimalInputs(messageID: $0.id) }

    let start = ContinuousClock.now
    var items: [MessageItem] = []
    items.reserveCapacity(messages.count)
    for (message, perMessageInputs) in zip(messages, inputs) {
      items.append(MessageFragmentBuilder.makeItem(
        for: message,
        inputs: perMessageInputs,
        envInputs: envInputs
      ))
    }
    let elapsed = ContinuousClock.now - start

    #expect(items.count == 1000)
    Self.benchmarkLogger.notice("Baseline build on main actor: \(String(describing: elapsed), privacy: .public)")
  }

  private static let benchmarkLogger = Logger(
    subsystem: "com.meshcoreone.tests",
    category: "MessageFragmentBuilderBenchmark"
  )
}
