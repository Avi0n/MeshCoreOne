import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("Removal authority through automatic recovery")
@MainActor
struct ConnectionRemovalRecoveryTests {
  @Test(arguments: [false, true], [false, true])
  func `failed unidentified handshake preserves removal`(
    internalDisconnect: Bool, unrelatedEndpoint: Bool
  ) async throws {
    let clock = TestClock()
    let env = try ConnectionManagerRadioTestHarness(sessionClock: clock)
    let removedKey = unrelatedEndpoint ? Data(repeating: 0xA8, count: 32) : env.wifi.publicKey
    let saved = DeviceDTO.testDevice(publicKey: removedKey, isActive: false).copy {
      $0.connectionMethods = [.wifi(host: "radio.test", port: 5123, displayName: nil)]
    }
    var connecting: Task<ConnectionResult, Error>?
    do {
      try await env.manager.persistenceStore.saveDevice(saved)
      await env.wifi.holdReplies(for: .appStart)
      let handshakeFinished = OSAllocatedUnfairLock(initialState: false)
      let initial = Task { @MainActor in
        defer { handshakeFinished.withLock { $0 = true } }
        return try await env.connect(.wifi)
      }
      connecting = initial
      try await waitUntil {
        await env.wifi.sentData.contains { $0.first == CommandCode.appStart.rawValue }
      }
      #expect(env.manager.desiredRequest?.attempt?.identity.publicKey == nil)
      try await env.manager.deleteDevice(id: saved.id)
      let firstRequest = try #require(env.manager.desiredRequest)
      #expect(!firstRequest.removedIdentities.isEmpty)
      let deleted = try #require(await env.manager.persistenceStore.fetchDevice(publicKey: removedKey))
      #expect(deleted.connectionMethods.isEmpty)

      await env.wifi.simulateRemoteLoss()
      try await advanceParkedMatchedSessionClock(clock, observation: {
        await sessionClockObservation(radio: env.wifi, session: env.manager.session)
      }) {
        handshakeFinished.withLock { $0 }
      }
      env.manager.sessionClockForTesting = nil
      let initialResult = await initial.result
      if case .success(.operational) = initialResult {
        Issue.record("The initial handshake must fail without acquiring identity")
      }
      await env.wifi.releaseAllGates()
      #expect(env.manager.currentConnection == nil)
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.manager.desiredRequest === firstRequest)
      if internalDisconnect {
        _ = await env.manager.disconnect(reason: .resyncFailed)
        #expect(env.manager.connectionIntent.wantsConnection)
      }

      await env.manager.checkWiFiConnectionHealth()
      await env.manager.retirementTask?.value
      await env.manager.metadataCommitTask?.value
      let remaining = try #require(await env.manager.persistenceStore.fetchDevice(publicKey: removedKey))
      #expect(remaining.radioID == saved.radioID)
      #expect(remaining.connectionMethods.isEmpty)
      #expect(!remaining.isActive)
      if unrelatedEndpoint {
        #expect(env.manager.connectionState == .ready)
        #expect(env.manager.connectedDevice?.publicKey == env.wifi.publicKey)
        #expect(env.manager.connectedDevice?.radioID != saved.radioID)
        #expect(env.manager.lastConnectedRadioID != saved.radioID)
      } else {
        #expect(env.manager.currentConnection == nil)
        #expect(!env.manager.connectionIntent.wantsConnection)
        #expect(env.manager.lastConnectedDeviceID == nil)
        #expect(env.manager.lastConnectedRadioID == nil)
        let explicit = try await env.connect(.wifi)
        #expect(env.manager.isCurrent(explicit))
        #expect(env.manager.connectedDevice?.radioID == saved.radioID)
      }
      let session = try #require(env.manager.session)
      let time = try await session.getTime()
      #expect(time.timeIntervalSince1970 > 0)
    } catch {
      await env.wifi.releaseAllGates()
      _ = await connecting?.result
      await env.close()
      throw error
    }
    await env.close()
  }
}
