import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity contracts")
@MainActor
struct CanonicalIdentityContractTests {
  @Test(arguments: [false, true], [false, true])
  func `config import adopts canonical WiFi ownership before later mutation and reconnect`(
    sourceWasPending: Bool, canonicalWasPending: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      let settings = services.settingsService
      let oldContext = settings.radioWiFiContextID
      let stateA = settings.radioWiFiState
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      if canonicalWasPending {
        let mutation = try #require(stateB.beginMutation(expectedRevision: nil))
        stateB.settle(mutation, outcome: .accepted)
      }
      if sourceWasPending { _ = try await settings.runCliCommand("set wifi.enabled 0") }
      let observedAt = ContinuousClock.now
      let before = RadioWiFiResetObservation(uptimeSeconds: 1000, startedAt: observedAt, completedAt: observedAt)
      let sourceCandidate = stateA.prepareExpectedReboot(contextID: oldContext, observation: before)
      let canonicalContext = UUID()
      let canonicalCandidate = stateB.prepareExpectedReboot(contextID: canonicalContext, observation: before)
      if let sourceCandidate {
        stateA.completeRebootSubmission(id: sourceCandidate, succeeded: true)
        stateA.noteLinkEnded(contextID: oldContext)
      }
      if let canonicalCandidate {
        stateB.completeRebootSubmission(id: canonicalCandidate, succeeded: true)
        stateB.noteLinkEnded(contextID: canonicalContext)
      }
      let revisionB = stateB.revision
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      let config = MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex)
      try await services.nodeConfigService.importConfig(
        config, sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )

      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      let canonicalSettings = try #require(harness.manager.services?.settingsService)
      #expect(canonicalSettings !== settings)
      #expect(canonicalSettings.radioWiFiState === stateB)
      #expect(canonicalSettings.radioWiFiContextID != oldContext)
      #expect(settings.radioWiFiContextID == oldContext)
      #expect(settings.radioWiFiState === stateA)
      #expect(stateB.revision > revisionB)
      #expect(stateB.rebootPending == (sourceWasPending || canonicalWasPending))
      #expect(stateA.expectedRebootID == nil)
      #expect(stateB.expectedRebootID == nil)
      let after = RadioWiFiResetObservation(uptimeSeconds: 1, startedAt: .now, completedAt: .now)
      if let sourceCandidate {
        #expect(!stateA.clearExpectedReboot(id: sourceCandidate, contextID: UUID(), readRevision: stateA.revision, observation: after))
      }
      if let canonicalCandidate {
        #expect(!stateB.clearExpectedReboot(id: canonicalCandidate, contextID: canonicalSettings.radioWiFiContextID, readRevision: stateB.revision, observation: after))
      }
      #expect(stateB.stableRevision() != nil)
      let framesBeforeOldCommand = await harness.bluetooth.sentData.count
      await #expect {
        try await settings.runCliCommand("set wifi.enabled 1")
      } throws: { $0 is CancellationError }
      #expect(await harness.bluetooth.sentData.count == framesBeforeOldCommand)
      _ = try await canonicalSettings.runCliCommand("set wifi.enabled 1")
      #expect(stateB.rebootPending)
      let afterWrite = stateB.revision
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      #expect(harness.manager.services?.settingsService.radioWiFiState === stateB)
      #expect(stateB.rebootPending)
      #expect(stateB.revision == afterWrite)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test
  func `config import continuation and a new queued send use canonical partition`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let services = try #require(harness.manager.services)
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      let contactKey = Data(repeating: 0xC1, count: ProtocolLimits.publicKeySize)
      let config = MeshCoreNodeConfig(
        privateKey: CanonicalIdentityFixture.privateKeyHex,
        channels: [.init(name: "Canonical", secret: String(repeating: "CD", count: 16))],
        contacts: [.init(
          type: 1, name: "Canonical contact", publicKey: contactKey.hexString,
          flags: 0, latitude: "0", longitude: "0", lastAdvert: 0, lastModified: 0
        )]
      )
      try await services.nodeConfigService.importConfig(
        config, sections: ConfigSections(nodeIdentity: true, channels: true, contacts: true),
        radioID: harness.deviceA.radioID
      )
      #expect(try await harness.manager.persistenceStore.fetchContacts(radioID: harness.deviceB.radioID)
        .contains { $0.publicKey == contactKey })
      #expect(try await harness.manager.persistenceStore.fetchChannels(radioID: harness.deviceB.radioID)
        .contains { $0.name == "Canonical" })
      let live = try #require(harness.manager.services)
      let pending = try await live.messageService.createPendingChannelMessage(
        text: "Canonical queue", channelIndex: 0, radioID: harness.deviceB.radioID
      )
      await harness.bluetooth.holdNextSubmission()
      try await live.chatSendQueueService.enqueueChannel(ChannelMessageEnvelope(
        messageID: pending.id, channelIndex: 0, isResend: false,
        messageText: pending.text, messageTimestamp: pending.timestamp, localNodeName: "Canonical radio"
      ))
      try await waitUntil("new canonical send should reach its physical gate") {
        await harness.bluetooth.submissionIsHeld
      }
      let rowsB = try await harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceB.radioID)
      let rowsA = try await harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceA.radioID)
      #expect(rowsB.contains { $0.messageID == pending.id })
      #expect(!rowsA.contains { $0.messageID == pending.id })
      await harness.bluetooth.releaseSubmission()
    }
  }
}
