import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("MessageFragmentBuilder rebuild-hash invariant")
@MainActor
struct MessageFootprintHashTests {
  /// Build a baseline `MessageItem` via the fixture helpers, then perturb a single
  /// render-affecting field on the underlying `MessageDTO` and assert the rebuilt
  /// item's `hashValue` changes. The invariant under test is the one documented
  /// on `MessageItem`: every render-affecting input must be encoded
  /// into `MessageItem`, so Equatable/Hashable detect every change.
  @Test
  func `sendCount change flips MessageItem.hashValue`() {
    let baseline = MessageFragmentBuilderFixtures.makeMessage(text: "hello")
    let bumped = MessageFragmentBuilderFixtures.makeMessage(text: "hello", sendCount: baseline.sendCount + 1)
    let inputs = MessageFragmentBuilderFixtures.makeInputs(messageID: baseline.id)
    let envInputs = MessageFragmentBuilderFixtures.makeEnvInputs(isOutgoing: true)

    let baseItem = MessageFragmentBuilder.makeItem(for: baseline, inputs: inputs, envInputs: envInputs)
    let bumpedItem = MessageFragmentBuilder.makeItem(for: bumped, inputs: inputs, envInputs: envInputs)

    #expect(baseItem != bumpedItem,
            "sendCount must be encoded into MessageItem; otherwise the status footer can't refresh after channel resend")
    #expect(baseItem.hashValue != bumpedItem.hashValue)
  }

  @Test
  func `heardRepeats change flips MessageItem.hashValue`() {
    let baseline = MessageFragmentBuilderFixtures.makeMessage(text: "hello", heardRepeats: 0)
    let bumped = MessageFragmentBuilderFixtures.makeMessage(text: "hello", heardRepeats: 1)
    let inputs = MessageFragmentBuilderFixtures.makeInputs(messageID: baseline.id)
    let envInputs = MessageFragmentBuilderFixtures.makeEnvInputs(isOutgoing: true)

    let baseItem = MessageFragmentBuilder.makeItem(for: baseline, inputs: inputs, envInputs: envInputs)
    let bumpedItem = MessageFragmentBuilder.makeItem(for: bumped, inputs: inputs, envInputs: envInputs)

    #expect(baseItem != bumpedItem)
  }

  @Test
  func `status change flips MessageItem.hashValue`() {
    let pending = MessageFragmentBuilderFixtures.makeMessage(text: "hello", status: .pending)
    let sent = MessageFragmentBuilderFixtures.makeMessage(text: "hello", status: .sent)
    let inputs = MessageFragmentBuilderFixtures.makeInputs(messageID: pending.id)
    let envInputs = MessageFragmentBuilderFixtures.makeEnvInputs(isOutgoing: true)

    let pendingItem = MessageFragmentBuilder.makeItem(for: pending, inputs: inputs, envInputs: envInputs)
    let sentItem = MessageFragmentBuilder.makeItem(for: sent, inputs: inputs, envInputs: envInputs)

    #expect(pendingItem != sentItem)
  }
}
