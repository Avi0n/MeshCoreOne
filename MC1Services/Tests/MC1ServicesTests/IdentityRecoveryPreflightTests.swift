import Foundation
@testable import MC1Services
import Testing

@Suite("Identity recovery preflight")
@MainActor
struct IdentityRecoveryPreflightTests {
  @Test(arguments: [false, true])
  func `a replaced source is refused before either transport dials`(viaWiFi: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      if viaWiFi {
        await harness.wifi.setDeviceID(harness.deviceA.id)
        _ = try await harness.manager.connectViaWiFi(host: "replaced-source.local", port: 4242)
      } else {
        try await harness.connectBluetooth()
      }
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let confirmation = try await IdentityRecoveryFixture.interrupt(harness)
      let replacementRadioID = UUID()
      let replacementKey = Data(repeating: 0xE7, count: ProtocolLimits.publicKeySize)
      try await harness.manager.persistenceStore.saveDevice(harness.deviceA.copy {
        $0.radioID = replacementRadioID
        $0.publicKey = replacementKey
      })
      let requestID = harness.manager.connectionRequestID
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
      #expect(harness.manager.connectionRequestID == requestID)
      #expect(harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == nil)
      let replacement = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id))
      #expect(replacement.radioID == replacementRadioID)
      #expect(replacement.publicKey == replacementKey)
      #expect(source.settingsService.radioWiFiState.rebootPending)
      #expect(!harness.manager.radioWiFiState(for: replacementRadioID).rebootPending)
      let sourceWasNotPublished = harness.manager.connectedDevice == nil && harness.manager.services == nil
      #expect(sourceWasNotPublished)
      await harness.wifi.setDeviceID(harness.deviceB.id)
      await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "unrelated-after-replacement.local", port: 4242)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  @Test(arguments: [false, true])
  func `cancelled or superseded preflight cannot consume its stale receipt or dial`(superseded: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await harness.connectBluetooth()
      let confirmation = try await IdentityRecoveryFixture.interrupt(harness)
      try await harness.manager.persistenceStore.deleteDevice(id: harness.deviceA.id)
      let gate = IdentityRecoveryFixture.Gate()
      harness.manager.testIdentityBeforeSourceValidation = { await gate.wait() }
      let initialRequestID = harness.manager.connectionRequestID
      let connectsBefore = await IdentityRecoveryFixture.connectCount(harness)
      let retrying = harness.track(Task {
        try await harness.manager.retryIdentityImport(
          confirmation: confirmation, privateKey: CanonicalIdentityFixture.privateKey
        )
      })
      var successor: Task<ConnectionResult, Error>?
      do {
        try await waitUntil("retry should hold its ordered source preflight before a physical dial") { gate.arrived }
        #expect(harness.manager.connectionRequestID == initialRequestID)
        if superseded {
          await harness.wifi.setDeviceID(harness.deviceB.id)
          await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
          successor = harness.track(Task {
            try await harness.manager.connectViaWiFi(host: "successor-during-preflight.local", port: 4242)
          })
          try await waitUntil("a fresh choice should invalidate the held confirmation") {
            harness.manager.connectionRequestID != initialRequestID
          }
        } else {
          retrying.cancel()
        }
        let connectsWhileHeld = await IdentityRecoveryFixture.connectCount(harness)
        #expect(connectsWhileHeld == connectsBefore)
        gate.open()
        _ = await retrying.result
        _ = try await successor?.value
        harness.manager.testIdentityBeforeSourceValidation = nil
        #expect(harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) != nil)
        let keyWritesAfterCancelledChoice = await IdentityRecoveryFixture.keyWriteCount(harness)
        #expect(keyWritesAfterCancelledChoice == 1)
        if !superseded {
          #expect(harness.manager.connectionRequestID == initialRequestID)
          await harness.wifi.setDeviceID(harness.deviceB.id)
          await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
          _ = try await harness.manager.connectViaWiFi(host: "successor-after-cancelled-preflight.local", port: 4242)
        }
        let currentConfirmation = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID))
        let successorRequestID = harness.manager.connectionRequestID
        let successorGeneration = harness.manager.currentConnection?.generation
        let connectsBeforeFreshConfirmation = await IdentityRecoveryFixture.connectCount(harness)
        await #expect(throws: (any Error).self) {
          _ = try await harness.manager.retryIdentityImport(
            confirmation: currentConfirmation, privateKey: CanonicalIdentityFixture.privateKey
          )
        }
        let connectsAfterFreshConfirmation = await IdentityRecoveryFixture.connectCount(harness)
        #expect(connectsAfterFreshConfirmation == connectsBeforeFreshConfirmation)
        #expect(harness.manager.prepareIdentityImportRecovery(receiptID: confirmation.receiptID) == nil)
        #expect(harness.manager.connectionRequestID == successorRequestID)
        #expect(harness.manager.currentConnection?.generation == successorGeneration)
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        gate.open()
        _ = await retrying.result
        _ = await successor?.result
        harness.manager.testIdentityBeforeSourceValidation = nil
        throw error
      }
    }
  }
}
