#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import Testing

@Suite("Identity recovery after association removal")
@MainActor
struct IdentityRecoveryAssociationTests {
  @Test
  func `native removal that demotes an idle source is validated without dialing its old association`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      registerSource(in: harness)
      try await harness.connectBluetooth()
      let confirmation = try await IdentityRecoveryFixture.interrupt(harness)
      harness.pairing.simulateAccessoryRemoved(harness.deviceA.id)
      await harness.manager.metadataCommitTask?.value
      let oldHandleWasRemoved = try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id) == nil
      #expect(oldHandleWasRemoved)
      let ghost = try #require(try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceA.radioID))
      #expect(ghost.id != harness.deviceA.id)
      #expect(ghost.publicKey == harness.deviceA.publicKey)
      #expect(ghost.connectionMethods.isEmpty)
      let connectsBefore = await IdentityRecoveryFixture.connectCount(harness)
      var validations = 0
      harness.manager.testIdentityBeforeSourceValidation = { validations += 1 }
      await #expect(throws: (any Error).self) {
        _ = try await harness.manager.retryIdentityImport(
          confirmation: confirmation, privateKey: CanonicalIdentityFixture.privateKey
        )
      }
      harness.manager.testIdentityBeforeSourceValidation = nil
      #expect(validations == 1)
      let connectsAfter = await IdentityRecoveryFixture.connectCount(harness)
      let keyWrites = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(connectsAfter == connectsBefore)
      #expect(keyWrites == 1)
      #expect(harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == nil)
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "unrelated-after-native-removal.local", port: 4242)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test(arguments: [false, true])
  func `surviving WiFi preserves uncertainty while an unavailable Bluetooth target cannot replace B`(viaWiFi: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      registerSource(in: harness)
      if viaWiFi {
        await harness.wifi.setDeviceID(harness.deviceA.id)
        _ = try await harness.manager.connectViaWiFi(host: "surviving-source.local", port: 4242)
      } else {
        try await harness.manager.persistenceStore.saveDevice(harness.deviceA.copy {
          $0.connectionMethods.append(.wifi(host: "surviving-source.local", port: 4242, displayName: nil))
        })
        try await harness.connectBluetooth()
      }
      let original = try await IdentityRecoveryFixture.interrupt(harness)
      harness.pairing.simulateAccessoryRemoved(harness.deviceA.id)
      await harness.manager.metadataCommitTask?.value
      let kept = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id))
      #expect(kept.radioID == harness.deviceA.radioID)
      #expect(kept.publicKey == harness.deviceA.publicKey)
      let onlyWiFiRemains = kept.connectionMethods.allSatisfy(\.isWiFi)
      #expect(onlyWiFiRemains)
      #expect(!kept.connectionMethods.isEmpty)
      let confirmation = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID))
      #expect(confirmation.capturedConnectionMethod == original.capturedConnectionMethod)
      if viaWiFi {
        _ = try await harness.manager.retryIdentityImport(
          confirmation: confirmation, privateKey: CanonicalIdentityFixture.privateKey
        )
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceA.radioID)
        #expect(harness.manager.identityImportRecoveries.isEmpty)
        let keyWrites = await IdentityRecoveryFixture.keyWriteCount(harness)
        #expect(keyWrites == 2)
      } else {
        await harness.wifi.setDeviceID(harness.deviceB.id)
        await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
        _ = try await harness.manager.connectViaWiFi(host: "current-b-during-association-retry.local", port: 4242)
        let currentConfirmation = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID))
        let successorRequestID = harness.manager.connectionRequestID
        let successorGeneration = harness.manager.currentConnection?.generation
        let connectsBefore = await IdentityRecoveryFixture.connectCount(harness)
        var validations = 0
        harness.manager.testIdentityBeforeSourceValidation = { validations += 1 }
        await #expect(throws: (any Error).self) {
          _ = try await harness.manager.retryIdentityImport(
            confirmation: currentConfirmation, privateKey: CanonicalIdentityFixture.privateKey
          )
        }
        harness.manager.testIdentityBeforeSourceValidation = nil
        #expect(validations == 1)
        let connectsAfter = await IdentityRecoveryFixture.connectCount(harness)
        let keyWrites = await IdentityRecoveryFixture.keyWriteCount(harness)
        #expect(connectsAfter == connectsBefore)
        #expect(keyWrites == 1)
        #expect(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID) != nil)
        #expect(harness.manager.connectionRequestID == successorRequestID)
        #expect(harness.manager.currentConnection?.generation == successorGeneration)
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      }
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  private func registerSource(in harness: ConnectionRadioHandoffHarness) {
    harness.pairing.isSessionActive = true
    harness.pairing.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: harness.deviceA.id, displayName: "Original source association")
    ])
  }
}
