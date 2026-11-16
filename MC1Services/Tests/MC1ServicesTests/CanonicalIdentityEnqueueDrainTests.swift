import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity enqueue drain")
@MainActor
struct CanonicalIdentityEnqueueDrainTests {
  @Test
  func `shutdown joins an enqueue already parked in actual persistence`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      let queue = services.chatSendQueueService
      let pending = try await services.messageService.createPendingChannelMessage(
        text: "Admitted before handoff", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      let gate = Gate()
      await harness.manager.persistenceStore.setPendingSendInsertHandler { await gate.wait() }
      let enqueuing = harness.track(Task {
        try await queue.enqueueChannel(ChannelMessageEnvelope(
          messageID: pending.id, channelIndex: 0, isResend: false, messageText: pending.text,
          messageTimestamp: pending.timestamp, localNodeName: "Old radio"
        ))
      })
      var stopped = false
      var stopping: Task<Void, Never>?
      do {
        try await waitUntil("actual PendingSend insert should remain held") { await gate.arrived }
        stopping = harness.track(Task { await queue.shutdown(); stopped = true })
        try await Task.sleep(for: .milliseconds(30))
        #expect(!stopped)
        await gate.open()
        _ = await enqueuing.result
        await stopping?.value
        #expect(stopped)
        #expect(try await harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceA.radioID)
          .contains { $0.messageID == pending.id })
        #expect(await harness.bluetooth.sentData.contains { $0.first == CommandCode.sendChannelMessage.rawValue } == false)
      } catch {
        await gate.open()
        _ = await enqueuing.result
        await stopping?.value
        await harness.manager.persistenceStore.setPendingSendInsertHandler(nil)
        throw error
      }
      await harness.manager.persistenceStore.setPendingSendInsertHandler(nil)
    }
  }

  private actor Gate {
    private(set) var arrived = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }
}
