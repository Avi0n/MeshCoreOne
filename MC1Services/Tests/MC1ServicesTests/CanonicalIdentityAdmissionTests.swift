import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity admission")
@MainActor
struct CanonicalIdentityAdmissionTests {
  @Test
  func `old queue admission closes before the private key reaches firmware`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      let oldQueue = services.chatSendQueueService
      let pending = try await services.messageService.createPendingChannelMessage(
        text: "Old partition draft", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      await harness.bluetooth.holdReplies(for: .importPrivateKey)
      let importing = harness.track(Task {
        try await services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      try await waitUntil("private key should reach firmware while its acknowledgement is held") {
        await harness.bluetooth.sentData.contains { $0.first == CommandCode.importPrivateKey.rawValue }
      }
      var acceptedOldEnqueue = false
      do {
        try await oldQueue.enqueueChannel(ChannelMessageEnvelope(
          messageID: pending.id, channelIndex: 0, isResend: false,
          messageText: pending.text, messageTimestamp: pending.timestamp, localNodeName: "Old radio"
        ))
        acceptedOldEnqueue = true
      } catch is CancellationError {}
      #expect(!acceptedOldEnqueue)
      #expect(try await harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceA.radioID)
        .contains { $0.messageID == pending.id } == false)
      await harness.bluetooth.releaseReplies(for: .importPrivateKey)
      try await importing.value
      let sent = await harness.bluetooth.sentData
      let importIndex = try #require(sent.firstIndex { $0.first == CommandCode.importPrivateKey.rawValue })
      #expect(!sent.dropFirst(importIndex + 1).contains { $0.first == CommandCode.sendChannelMessage.rawValue })
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `cancelled import waiter cannot abandon accepted canonical evidence transfer`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      _ = try await services.settingsService.runCliCommand("set wifi.enabled 0")
      let canonical = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      let gate = IdentityRecoveryFixture.Gate()
      harness.manager.testIdentityAfterCommit = { await gate.wait() }
      let importing = harness.track(Task {
        try await services.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      do {
        try await waitUntil("accepted canonical store result should hold before evidence transfer") { gate.arrived }
        #expect(!canonical.rebootPending)
        importing.cancel()
        gate.open()
        _ = await importing.result
      } catch {
        gate.open()
        _ = await importing.result
        harness.manager.testIdentityAfterCommit = nil
        throw error
      }
      harness.manager.testIdentityAfterCommit = nil
      await harness.manager.metadataCommitTask?.value
      let fresh = try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)
      #expect(fresh?.radioID == harness.deviceB.radioID)
      #expect(canonical.rebootPending)
      #expect(canonical.stableRevision() == nil)
      let cancelledOwnerWasNotPublished = harness.manager.connectedDevice == nil && harness.manager.services == nil
      #expect(cancelledOwnerWasNotPublished)
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }
}
