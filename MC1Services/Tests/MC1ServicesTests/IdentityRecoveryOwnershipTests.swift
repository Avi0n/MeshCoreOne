import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Identity recovery ownership")
@MainActor
struct IdentityRecoveryOwnershipTests {
  @Test(arguments: [false, true])
  func `confirmed removal revokes retry and follows any accepted canonical result`(holdAfterCommit: Bool) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let recovery = try await IdentityRecoveryFixture.interrupt(harness)
      let gate = IdentityRecoveryFixture.Gate()
      if holdAfterCommit {
        harness.manager.testIdentityAfterCommit = { await gate.wait() }
      } else {
        harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      }
      let retrying = harness.track(Task {
        try await harness.manager.retryIdentityImport(confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey)
      })
      var removing: Task<ConnectionRemovalResult, Error>?
      do {
        try await waitUntil("retry should reach the removal boundary") { gate.arrived }
        let context = try await harness.manager.captureRemoval(id: harness.deviceA.id)
        let requestID = harness.manager.connectionRequestID
        removing = harness.track(Task {
          try await harness.manager.removeDevice(
            context, policy: .wholeDevice(deleteData: false), reason: .forgetDevice, systemPolicy: .bestEffort
          )
        })
        try await waitUntil("confirmed removal should revoke the captured request") {
          harness.manager.connectionRequestID != requestID
        }
        gate.open()
        _ = await retrying.result
        _ = try await removing?.value
        harness.manager.testIdentityBeforeCommit = nil
        harness.manager.testIdentityAfterCommit = nil
        let oldHandleIsAbsent = try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id) == nil
        #expect(oldHandleIsAbsent)
        let expectedRadioID = holdAfterCommit ? harness.deviceB.radioID : harness.deviceA.radioID
        let stored = try #require(try await harness.manager.persistenceStore.fetchDevice(radioID: expectedRadioID))
        #expect(stored.id != harness.deviceA.id)
        #expect(stored.publicKey == (holdAfterCommit ? CanonicalIdentityFixture.publicKey : harness.deviceA.publicKey))
        #expect(stored.connectionMethods.isEmpty)
        #expect(stored.radioID == expectedRadioID)
        #expect(harness.manager.radioWiFiState(for: harness.deviceB.radioID).rebootPending == holdAfterCommit)
        let wasNotPublished = harness.manager.connectedDevice == nil && harness.manager.services == nil
        #expect(wasNotPublished)
        #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
        await harness.wifi.setDeviceID(harness.deviceB.id)
        await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
        _ = try await harness.manager.connectViaWiFi(host: "unrelated-after-removal.local", port: 4242)
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        gate.open()
        _ = await retrying.result
        _ = await removing?.result
        harness.manager.testIdentityBeforeCommit = nil
        harness.manager.testIdentityAfterCommit = nil
        throw error
      }
    }
  }

  @Test(arguments: [false, true], [false, true])
  func `retry cancellation and supersession respect the actual durable admission boundary`(
    holdAfterCommit: Bool, cancelWaiter: Bool
  ) async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      _ = try await source.settingsService.runCliCommand("set wifi.enabled 0")
      let original = try await IdentityRecoveryFixture.interrupt(harness)
      let gate = IdentityRecoveryFixture.Gate()
      if holdAfterCommit {
        harness.manager.testIdentityAfterCommit = { await gate.wait() }
      } else {
        harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      }
      let retrying = harness.track(Task {
        try await harness.manager.retryIdentityImport(confirmation: original, privateKey: CanonicalIdentityFixture.privateKey)
      })
      var successor: Task<ConnectionResult, Error>?
      do {
        try await waitUntil("retry should reach its actual durable boundary") { gate.arrived }
        if cancelWaiter {
          retrying.cancel()
          try await waitUntil("cancellation should revoke the retry worker before the gate opens") {
            harness.manager.desiredRequest?.worker?.isCancelled == true
          }
        } else {
          await harness.wifi.setDeviceID(harness.deviceB.id)
          await harness.wifi.setPublicKey(harness.deviceB.publicKey, for: harness.deviceB.id)
          let oldRequest = harness.manager.connectionRequestID
          successor = harness.track(Task { try await harness.manager.connectViaWiFi(host: "successor.local", port: 4242) })
          try await waitUntil("new choice should supersede the captured retry") {
            harness.manager.connectionRequestID != oldRequest
          }
        }
        gate.open()
        _ = await retrying.result
        _ = try await successor?.value
        harness.manager.testIdentityBeforeCommit = nil
        harness.manager.testIdentityAfterCommit = nil
        let stored = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.deviceA.id))
        #expect(stored.radioID == (holdAfterCommit ? harness.deviceB.radioID : harness.deviceA.radioID))
        #expect(harness.manager.radioWiFiState(for: harness.deviceB.radioID).rebootPending == holdAfterCommit)
        if holdAfterCommit {
          #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id] == nil)
        } else {
          #expect(harness.manager.pendingIdentityReconciliations[harness.deviceA.id]?.publicKey == CanonicalIdentityFixture.publicKey)
        }
        if !cancelWaiter {
          #expect(harness.manager.connectedDevice?.publicKey == harness.deviceB.publicKey)
          #expect(harness.manager.services?.settingsService.radioWiFiState.rebootPending == false)
          #expect(try await harness.manager.services?.settingsService.getTime() != nil)
        }
        let writesBeforeKnownRetry = await IdentityRecoveryFixture.keyWriteCount(harness)
        _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
        let writesAfterKnownRetry = await IdentityRecoveryFixture.keyWriteCount(harness)
        #expect(writesAfterKnownRetry == writesBeforeKnownRetry)
        #expect(harness.manager.connectedDevice?.radioID == harness.deviceB.radioID)
        #expect(try await harness.manager.services?.settingsService.getTime() != nil)
      } catch {
        gate.open()
        _ = await retrying.result
        _ = await successor?.result
        harness.manager.testIdentityBeforeCommit = nil
        harness.manager.testIdentityAfterCommit = nil
        throw error
      }
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `cancelled identity recovery settles while its link is still retiring`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness(wifiIsSameRadio: false) { harness in
      try await harness.connectBluetooth()
      let original = try await IdentityRecoveryFixture.interrupt(harness)
      await harness.wifi.setDeviceID(harness.deviceB.id)
      _ = try await harness.manager.connectViaWiFi(host: "other.local", port: 4242)
      let recovery = try #require(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID))
      let previousRequest = harness.manager.connectionRequestID
      let gate = IdentityRecoveryFixture.Gate()
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let retry = harness.track(Task {
        try await harness.manager.retryIdentityImport(
          confirmation: recovery, privateKey: CanonicalIdentityFixture.privateKey
        )
      })
      do {
        try await waitUntil("recovery must be admitted and waiting for the unrelated link to retire") {
          gate.arrived && harness.manager.connectionRequestID != previousRequest
        }
        #expect(harness.manager.connectionState == .connecting)
        let connectCount = await IdentityRecoveryFixture.connectCount(harness)
        let keyWrites = await IdentityRecoveryFixture.keyWriteCount(harness)
        retry.cancel()
        try await waitUntil("UI cancellation must reach the admitted recovery worker") {
          harness.manager.desiredRequest?.worker?.isCancelled == true
        }
        gate.open()
        harness.manager.testConnectionTeardownHandler = nil
        let result = try await retry.value
        if case .cancelled = result {} else { Issue.record("Expected cancelled recovery result") }
        await harness.manager.retirementTask?.value
        #expect(harness.manager.currentConnection == nil)
        #expect(harness.manager.desiredRequest?.attempt == nil)
        #expect(harness.manager.connectionTeardownCount == 0)
        #expect(await IdentityRecoveryFixture.connectCount(harness) == connectCount)
        #expect(await IdentityRecoveryFixture.keyWriteCount(harness) == keyWrites)
        #expect(harness.manager.prepareIdentityImportRecovery(receiptID: original.receiptID) != nil)
        #expect(harness.manager.desiredRequest?.target.deviceID == harness.deviceA.id)
        #expect(harness.manager.connectionIntent == .wantsConnection(forceFullSync: false))
        #expect(harness.manager.connectionState == .disconnected)
        await harness.bluetooth.releaseAllGates()
        await harness.wifi.releaseAllGates()
        await harness.manager.checkBLEConnectionHealth()
        await harness.manager.checkWiFiConnectionHealth()
        #expect(harness.manager.connectionState != .connecting)
        if harness.manager.connectionState == .ready {
          #expect(try await harness.manager.services?.settingsService.getTime() != nil)
        }
      } catch {
        gate.open()
        harness.manager.testConnectionTeardownHandler = nil
        throw error
      }
    }
  }
}
