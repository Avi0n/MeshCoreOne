import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity recovery source validation")
@MainActor
struct IdentityRecoverySourceValidationTests {
  @Test(arguments: SourceChange.allCases, [false, true])
  func `fresh source validation refuses a deleted demoted or replaced original`(
    change: SourceChange, afterConfirmedImport: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let recovery = try await IdentityRecoveryFixture.interrupt(harness)
      let gate = IdentityRecoveryFixture.Gate()
      if afterConfirmedImport {
        harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      } else {
        await harness.bluetooth.setPublicKey(harness.deviceA.publicKey, for: harness.deviceA.id)
        harness.manager.testIdentityBeforeSourceValidation = { await gate.wait() }
      }
      let operation = harness.track(Task {
        if afterConfirmedImport {
          return try await harness.manager.retryIdentityImport(confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey)
        }
        return try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      })
      do {
        try await waitUntil("source validation should hold before its actor operation") { gate.arrived }
        try await change.apply(to: harness)
        gate.open()
        _ = await operation.result
        harness.manager.testIdentityBeforeSourceValidation = nil
        harness.manager.testIdentityBeforeCommit = nil
        let wasNotPublished = harness.manager.connectedDevice == nil && harness.manager.services == nil
        #expect(wasNotPublished)
        #expect(harness.manager.identityImportRecoveries.isEmpty)
        #expect(!harness.manager.radioWiFiState(for: harness.deviceB.radioID).rebootPending)
        let stored = try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id)
        switch change {
        case .deleted: #expect(stored == nil)
        case .demoted:
          #expect(stored == nil)
          let ghost = try #require(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceA.radioID))
          #expect(ghost.id != harness.deviceA.id)
          #expect(ghost.publicKey == harness.deviceA.publicKey)
          #expect(ghost.connectionMethods.isEmpty)
        case .replaced:
          #expect(stored?.publicKey == replacementKey)
          #expect(stored?.radioID == replacementRadioID)
        }
        let writes = await IdentityRecoveryFixture.keyWriteCount(harness)
        #expect(writes == (afterConfirmedImport ? 2 : 1))
        await harness.wifi.setDeviceID(harness.deviceB.id)
        await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
        _ = try await harness.manager.connectViaWiFi(host: "fresh-choice.local", port: 4242)
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        gate.open()
        _ = await operation.result
        harness.manager.testIdentityBeforeSourceValidation = nil
        harness.manager.testIdentityBeforeCommit = nil
        throw error
      }
    }
  }

  private static let replacementKey = Data(repeating: 0xE2, count: ProtocolLimits.publicKeySize)
  private static let replacementRadioID = UUID()
  private var replacementKey: Data {
    Self.replacementKey
  }

  private var replacementRadioID: UUID {
    Self.replacementRadioID
  }

  enum SourceChange: CaseIterable, Sendable {
    case deleted, demoted, replaced

    @MainActor
    func apply(to harness: ConnectionRadioHandoffHarness) async throws {
      let store = harness.manager.persistenceStore
      switch self {
      case .deleted: try await store.deleteDevice(id: harness.deviceA.id)
      case .demoted: try await store.demoteDeviceToGhost(id: harness.deviceA.id)
      case .replaced:
        try await store.saveDevice(harness.deviceA.copy {
          $0.publicKey = IdentityRecoverySourceValidationTests.replacementKey
          $0.radioID = IdentityRecoverySourceValidationTests.replacementRadioID
        })
      }
    }
  }
}
