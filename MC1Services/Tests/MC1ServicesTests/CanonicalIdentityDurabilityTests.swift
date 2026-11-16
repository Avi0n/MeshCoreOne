import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity durability")
@MainActor
struct CanonicalIdentityDurabilityTests {
  @Test(arguments: [false, true])
  func `failed canonical save cannot leave staged partition or ghost deletion for a later save`(
    pendingRxLog: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let stateA = source.settingsService.radioWiFiState
      let stateB = harness.manager.radioWiFiState(for: harness.deviceB.radioID)
      let entry = RxLogEntryDTO(
        radioID: harness.deviceA.radioID,
        from: ParsedRxLogData(
          snr: 10, rssi: -65, rawPayload: Data([0x15]), routeType: .flood,
          payloadType: .groupText, payloadVersion: 0, payloadTypeBits: 5, transportCode: nil,
          pathLength: 0, pathNodes: [], packetPayload: Data([0xAB])
        )
      )
      if pendingRxLog {
        harness.manager.testIdentityBeforeCommit = {
          do { try await harness.manager.persistenceStore.saveRxLogEntry(entry) } catch { Issue.record(error) }
        }
      }
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      await harness.manager.persistenceStore.setIdentityReconciliationFaultInjection { throw Failure.save }
      await #expect(throws: (any Error).self) {
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      }
      await harness.manager.persistenceStore.setIdentityReconciliationFaultInjection(nil)
      harness.manager.testIdentityBeforeCommit = nil
      #expect(harness.manager.services == nil)
      #expect(stateA.rebootPending)
      #expect(!stateB.rebootPending)
      #expect(stateA.stableRevision() == nil)
      if pendingRxLog {
        #expect(await harness.manager.persistenceStore.unsavedRxLogInsertCount == 0)
        #expect(try await harness.manager.persistenceStore.fetchRxLogEntries(radioID: harness.deviceA.radioID)
          .contains { $0.id == entry.id })
      }
      let a = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id))
      #expect(a.radioID == harness.deviceA.radioID)
      #expect(a.publicKey == harness.deviceA.publicKey)
      #expect(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceB.radioID)?.radioID == harness.deviceB.radioID)
      _ = try await source.messageService.createPendingChannelMessage(
        text: "Later unrelated commit", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceB.radioID) != nil)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id]?.publicKey == CanonicalIdentityFixture.publicKey)
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
      #expect(harness.manager.connectedDevice?.id == harness.deviceA.id)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(stateB.rebootPending)
      #expect(stateB.stableRevision() != nil)
      #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      if pendingRxLog {
        #expect(try await harness.manager.persistenceStore.fetchRxLogEntries(radioID: harness.deviceA.radioID)
          .contains { $0.id == entry.id })
      }
    }
  }

  @Test(arguments: [false, true], [false, true])
  func `accepted canonical effects finish after supersession without borrowing its identity`(
    holdAfterCommit: Bool, sameRadio: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let gate = Gate()
      if holdAfterCommit {
        harness.manager.testIdentityAfterCommit = { await gate.wait() }
      } else {
        harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      }
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      let importing = harness.track(Task {
        try await source.nodeConfigService.importConfig(
          MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
          sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
        )
      })
      var successor: Task<ConnectionResult, Error>?
      do {
        try await waitUntil("accepted identity should reach its real store boundary") { gate.arrived }
        let key = sameRadio ? CanonicalIdentityFixture.publicKey : Data(repeating: 0xC6, count: ProtocolLimits.publicKeySize)
        await harness.wifi.setDeviceID(harness.deviceB.id)
        await harness.wifi.setPublicKey(key, for: harness.deviceB.id)
        let oldRequest = harness.manager.desiredRequest?.id
        successor = harness.track(Task { try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242) })
        try await waitUntil("successor request should supersede the captured importer") {
          harness.manager.desiredRequest?.id != oldRequest
        }
        gate.open()
        _ = await importing.result
        _ = try await successor?.value
        #expect(harness.manager.connectedDevice?.publicKey == key)
        #expect((harness.manager.connectedDevice?.radioID == harness.deviceB.radioID) == sameRadio)
        let canonicalResultWasAccepted = holdAfterCommit || sameRadio
        #expect(harness.manager.radioWiFiState(for: harness.deviceB.radioID).rebootPending == canonicalResultWasAccepted)
        #expect(harness.manager.services?.settingsService.radioWiFiState.rebootPending == sameRadio)
        #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)?.radioID
          == (canonicalResultWasAccepted ? harness.deviceB.radioID : harness.deviceA.radioID))
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        gate.open()
        _ = await importing.result
        _ = await successor?.result
        harness.manager.testIdentityBeforeCommit = nil
        harness.manager.testIdentityAfterCommit = nil
        throw error
      }
      harness.manager.testIdentityBeforeCommit = nil
      harness.manager.testIdentityAfterCommit = nil
    }
  }

  @Test
  func `identity import with no ghost preserves the current partition and child rows`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      await harness.bluetooth.setChannel(index: 0, name: "Current channel", secret: Data(repeating: 0xA1, count: 16))
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let history = try await source.messageService.createPendingChannelMessage(
        text: "Retain current partition history", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let state = source.settingsService.radioWiFiState
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      try await source.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceA.radioID)
      #expect(harness.manager.connectedDevice?.publicKey == CanonicalIdentityFixture.publicKey)
      #expect(harness.manager.services?.settingsService.radioWiFiState === state)
      #expect(state.rebootPending)
      #expect(try await harness.manager.persistenceStore.fetchMessage(id: history.id)?.radioID == harness.deviceA.radioID)
      #expect(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceB.id) != nil)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  private enum Failure: Error { case save }

  @MainActor
  private final class Gate {
    var arrived = false
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
