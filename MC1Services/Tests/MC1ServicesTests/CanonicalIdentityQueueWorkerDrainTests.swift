import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity queue worker drain")
@MainActor
struct CanonicalIdentityQueueWorkerDrainTests {
  @Test
  func `identity import joins an old queue worker held before its atomic attempt bump`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      await harness.bluetooth.setChannel(index: 0, name: "Canonical", secret: Data(repeating: 0xCD, count: 16))
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let queue = source.chatSendQueueService
      let store = harness.manager.persistenceStore
      let pending = try await source.messageService.createPendingChannelMessage(
        text: "Old worker must drain", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      let gate = Gate()
      await store.setPendingSendAttemptHandler { await gate.wait() }
      try await queue.enqueueChannel(envelope(pending))
      var importing: Task<Void, Error>?
      do {
        try await waitUntil("actual queue attempt persistence should be held") { await gate.arrived }
        let heldRows = try await store.fetchPendingSends(radioID: harness.deviceA.radioID)
        #expect(heldRows.first { $0.messageID == pending.id }?.attemptCount == 0)
        await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
        importing = harness.track(Task {
          try await source.nodeConfigService.importConfig(
            MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
            sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
          )
        })
        try await waitUntil("identity import should close old queue admission") { queue.isRetiredForTesting }
        try await Task.sleep(for: .milliseconds(30))
        #expect(await harness.bluetooth.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue } == false)
        #expect(await gate.finished == false)
        await gate.open()
        try await importing?.value
        await store.setPendingSendAttemptHandler(nil)
        await queue.awaitDrainCompletion()
        #expect(await gate.finished)
        let rowsA = try await store.fetchPendingSends(radioID: harness.deviceA.radioID)
        #expect(rowsA.first { $0.messageID == pending.id }?.attemptCount == 1)
        let current = try #require(harness.manager.services)
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
        #expect(try await current.settingsService.getTime().timeIntervalSince1970 > 0)
        let fresh = try await current.messageService.createPendingChannelMessage(
          text: "Canonical queue is live", channelIndex: 0, radioID: harness.deviceB.radioID
        )
        try await current.chatSendQueueService.enqueueChannel(envelope(fresh))
        try await waitUntil("fresh B queue should finish its actual send") {
          let message = try? await store.fetchMessage(id: fresh.id)
          let rows = await (try? store.fetchPendingSends(radioID: harness.deviceB.radioID)) ?? []
          return message?.status == .sent && !rows.contains { $0.messageID == fresh.id }
        }
        let frames = await harness.bluetooth.sentData
        #expect(frames.contains { $0.range(of: Data(pending.text.utf8)) != nil } == false)
        #expect(frames.contains { $0.range(of: Data(fresh.text.utf8)) != nil })
      } catch {
        await gate.open()
        await store.setPendingSendAttemptHandler(nil)
        _ = await importing?.result
        await queue.awaitDrainCompletion()
        throw error
      }
    }
  }

  private func envelope(_ message: MessageDTO) -> ChannelMessageEnvelope {
    ChannelMessageEnvelope(
      messageID: message.id, channelIndex: 0, isResend: false, messageText: message.text,
      messageTimestamp: message.timestamp, localNodeName: "Queue drain radio"
    )
  }

  private actor Gate {
    private(set) var arrived = false
    private(set) var finished = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      if !opened { await withCheckedContinuation { continuation = $0 } }
      finished = true
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }
}
