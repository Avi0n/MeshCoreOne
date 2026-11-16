#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import Testing

@Suite("Idle identity recovery removal")
@MainActor
struct IdleIdentityRecoveryRemovalTests {
  @Test(arguments: [false, true], [false, true])
  func `retry of a removed idle source validates and disposes its recovery before another dial`(
    deleteData: Bool, viaWiFi: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      harness.pairing.isSessionActive = true
      harness.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: harness.deviceA.id, displayName: "Unresolved source")
      ])
      if viaWiFi {
        await harness.wifi.setDeviceID(harness.deviceA.id)
        _ = try await harness.manager.connectViaWiFi(host: "removed-source.local", port: 4242)
      } else {
        try await harness.connectBluetooth()
      }
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let history = try await source.messageService.createPendingChannelMessage(
        text: "Original saved data", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      let confirmation = try await IdentityRecoveryFixture.interrupt(harness)
      let sourceWasTerminal = harness.manager.desiredRequest?.target == .disconnected
        && harness.manager.connectedDevice == nil && harness.manager.services == nil
      #expect(sourceWasTerminal)
      let terminalRequestID = harness.manager.connectionRequestID
      if deleteData {
        let context = try await harness.manager.captureRemoval(id: harness.deviceA.id)
        _ = try await harness.manager.forgetDevice(context, deleteData: true)
      } else {
        try await harness.manager.deleteDevice(id: harness.deviceA.id)
      }
      #expect(harness.pairing.removeAccessoryCallCount == 1)
      let associationWasRemoved = harness.pairing.accessory(for: harness.deviceA.id) == nil
      #expect(associationWasRemoved)
      #expect(harness.manager.connectionRequestID == terminalRequestID)
      let oldHandleWasRemoved = try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id) == nil
      #expect(oldHandleWasRemoved)
      let retained = try await harness.manager.persistenceStore.fetchDevice(radioID: harness.deviceA.radioID)
      let storedHistory = try await harness.manager.persistenceStore.fetchMessage(id: history.id)
      if deleteData {
        #expect(retained == nil)
        #expect(storedHistory == nil)
      } else {
        let ghost = try #require(retained)
        #expect(ghost.id != harness.deviceA.id)
        #expect(ghost.publicKey == harness.deviceA.publicKey)
        #expect(ghost.connectionMethods.isEmpty)
        #expect(storedHistory?.radioID == harness.deviceA.radioID)
      }
      #expect(source.settingsService.radioWiFiState.rebootPending)
      let connectsBeforeStaleConfirmation = await IdentityRecoveryFixture.connectCount(harness)
      var sourceValidationCount = 0
      harness.manager.testIdentityBeforeSourceValidation = { sourceValidationCount += 1 }
      await #expect(throws: (any Error).self) {
        _ = try await harness.manager.retryIdentityImport(
          confirmation: confirmation, privateKey: CanonicalIdentityFixture.privateKey
        )
      }
      harness.manager.testIdentityBeforeSourceValidation = nil
      #expect(sourceValidationCount == 1)
      let connectsAfterStaleConfirmation = await IdentityRecoveryFixture.connectCount(harness)
      let keyWritesAfterStaleConfirmation = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(connectsAfterStaleConfirmation == connectsBeforeStaleConfirmation)
      #expect(keyWritesAfterStaleConfirmation == 1)
      #expect(harness.manager.connectionRequestID == terminalRequestID)
      let failedRetryDidNotRestoreRecovery = harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == nil
      #expect(failedRetryDidNotRestoreRecovery)
      let receiptWasDisposed = harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil
      #expect(receiptWasDisposed)

      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "unrelated-after-idle-removal.local", port: 4242)
      let successorRequestID = harness.manager.connectionRequestID
      let successorGeneration = harness.manager.currentConnection?.generation
      await #expect(throws: (any Error).self) {
        _ = try await harness.manager.retryIdentityImport(
          confirmation: confirmation, privateKey: CanonicalIdentityFixture.privateKey
        )
      }
      #expect(harness.manager.connectionRequestID == successorRequestID)
      #expect(harness.manager.currentConnection?.generation == successorGeneration)
      #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
      #expect(harness.manager.services?.settingsService.radioWiFiState.rebootPending == false)
      let finalKeyWriteCount = await IdentityRecoveryFixture.keyWriteCount(harness)
      #expect(finalKeyWriteCount == 1)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }
}
