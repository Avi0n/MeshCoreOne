import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Autonomous connection result ownership")
@MainActor
struct ConnectionManagerAutonomousPortTests {
  enum Entry {
    case foreground
    case savedWiFi
  }

  enum Outcome {
    case success
    case failure
    case cancellation
  }

  struct HandshakeSample: Sendable {
    var entry: Entry
    var outcome: Outcome
  }

  /// Foreground covers each way the old handshake ends. Saved WiFi is the entry that starts on the other transport.
  nonisolated static let handshakeSamples: [HandshakeSample] = [
    HandshakeSample(entry: .foreground, outcome: .success),
    HandshakeSample(entry: .foreground, outcome: .failure),
    HandshakeSample(entry: .foreground, outcome: .cancellation),
    HandshakeSample(entry: .savedWiFi, outcome: .success),
  ]

  @Test(.timeLimit(.minutes(1)), arguments: handshakeSamples)
  func `superseded autonomous handshake cannot resume fallback or replace the admitted choice`(_ sample: HandshakeSample) async throws {
    let entry = sample.entry
    let outcome = sample.outcome
    let env = try ConnectionManagerRadioTestHarness()
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: env.deviceID, displayName: nil)
    let wifi = ConnectionMethod.wifi(host: "saved.test", port: 5123, displayName: nil)
    let saved = DeviceDTO.testDevice(id: env.deviceID, isActive: false).copy {
      $0.connectionMethods = entry == .savedWiFi ? [bluetooth, wifi] : [wifi, bluetooth]
    }
    try await env.manager.persistenceStore.saveDevice(saved)
    env.manager.testLastConnectedDeviceID = saved.id
    env.manager.setTestState(connectionIntent: .wantsConnection())
    await env.manager.wireTransportHandlers()
    let oldTransport = entry == .savedWiFi ? env.wifi : env.ble
    let nextTransport = entry == .savedWiFi ? env.ble : env.wifi
    let nextType: TransportType = entry == .savedWiFi ? .bluetooth : .wifi
    await oldTransport.holdReplies(for: .appStart)
    if outcome == .failure { await oldTransport.rejectNext(.appStart) }
    var synced: [UUID] = []
    var authFailures: [UUID] = []
    env.manager.onDeviceSynced = { snapshot, _ in synced.append(snapshot.generation) }
    env.manager.onAuthenticationFailure = { authFailures.append($0) }
    let recovery = Task {
      switch entry {
      case .foreground: await env.manager.checkBLEConnectionHealth()
      case .savedWiFi: await env.manager.checkWiFiConnectionHealth()
      }
    }
    var replacement: Task<ConnectionResult, Error>?
    do {
      try await waitUntil("the autonomous entry must reach a real held handshake result") {
        await oldTransport.heldReplyCount(for: .appStart) == 1
      }
      let request = try #require(env.manager.desiredRequest)
      let resource = try #require(request.attempt?.resource)
      replacement = Task { try await env.connect(nextType) }
      try await waitUntil("the explicit choice must supersede the autonomous request") {
        env.manager.connectionRequestID != request.id
      }
      let nextRequestID = env.manager.connectionRequestID
      #expect(!resource.binding.isActive)
      #expect(env.manager.currentConnection == nil)
      #expect(await nextTransport.connectCount == 0)
      if outcome == .cancellation { recovery.cancel() }
      await oldTransport.releaseReplies(for: .appStart)
      await recovery.value
      let result = try await #require(replacement).value
      #expect(env.manager.isCurrent(result))
      let current = try #require(env.manager.currentConnection)
      #expect(env.manager.connectionRequestID == nextRequestID)
      #expect(current.transportType == nextType)
      #expect(current.device.radioID == saved.radioID)
      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(!env.manager.isReconnectionWatchdogRunning)
      #expect(synced == [current.generation])
      #expect(authFailures.isEmpty)
      #expect(await oldTransport.connectCount == 1)
      #expect(await nextTransport.connectCount == 1)
      #expect(await resource.session.subscriberCountForTest == 0)
      #expect(try await current.services.session.getTime().timeIntervalSince1970 > 0)
    } catch {
      recovery.cancel()
      replacement?.cancel()
      await env.ble.releaseAllGates()
      await env.wifi.releaseAllGates()
      await recovery.value
      _ = await replacement?.result
      await env.close()
      throw error
    }
    await env.close()
  }
}
