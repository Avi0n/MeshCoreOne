import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionManager Session Tests")
@MainActor
struct ConnectionManagerSessionTests {
  // MARK: - Request and cycle projections

  @Test
  func `activeConnectionAttemptDeviceID prefers session rebuild device`() throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    let request = manager.seedBLERecoveryRequest(deviceID: deviceID)
    _ = manager.reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)

    #expect(manager.activeConnectionAttemptDeviceID == deviceID)
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
  }

  @Test
  func `connect to same device returns early while session rebuild is in progress`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let deviceID = UUID()

    manager.testLastConnectedDeviceID = deviceID
    _ = manager.seedBLERecoveryRequest(deviceID: deviceID)
    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      sessionRebuildDeviceID: deviceID
    )

    try await manager.connect(to: deviceID)

    #expect(manager.connectionState == .disconnected)
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(manager.connectionIntent == .wantsConnection())
  }

  // MARK: - Recovery failure policy

  @Test
  func `owned recovery failure clears state and notifies connection loss`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    manager.updateDevice(with: DeviceDTO.testDevice())
    manager.setTestState(
      connectionState: .connected,
      connectionIntent: ConnectionIntent.none
    )

    var lossCount = 0
    manager.onConnectionLost = { _ in lossCount += 1 }
    await manager.finishSeededBLERecovery()

    #expect(lossCount == 1)
    #expect(manager.connectionState == .disconnected)
    #expect(manager.connectedDevice == nil)
    #expect(manager.allowedRepeatFreqRanges.isEmpty)
  }

  @Test(.timeLimit(.minutes(1)), arguments: HealthCheckExclusion.allCases)
  func `WiFi health checks preserve an excluded request without dialing`(_ exclusion: HealthCheckExclusion) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    var releaseRecovery: CheckedContinuation<Void, Never>?
    do {
      switch exclusion {
      case .recoveryInProgress:
        await env.wifi.setConnectError(MeshTransportError.notConnected)
        await #expect(throws: ConnectionRequestFailure.self) { _ = try await env.connect(.wifi) }
        var recoveryEntered = false
        env.manager.testWiFiRecoveryEntryHandler = {
          await withCheckedContinuation {
            releaseRecovery = $0
            recoveryEntered = true
          }
        }
        env.manager.startWiFiReconnection()
        try await waitUntil("the recovery worker should enter before the health check") { recoveryEntered }
        #expect(env.manager.wifiReconnectTask != nil)
      case .withoutIntent:
        _ = try await env.connect(.wifi)
        _ = await env.manager.disconnect(reason: .userInitiated)
        #expect(!env.manager.connectionIntent.wantsConnection)
      case .bluetoothRequest:
        _ = try await env.connect(.wifi)
        await env.ble.setConnectError(BLEError.deviceNotFound)
        await #expect(throws: ConnectionRequestFailure.self) { _ = try await env.connect(.bluetooth) }
        #expect(env.manager.desiredRequest?.target == .bluetooth(env.deviceID))
      }
      #expect(env.manager.connectionState == .disconnected)
      let requestID = env.manager.connectionRequestID
      let recoverySlot = env.manager.wifiRecoverySlotID
      let wifiDials = await env.wifi.connectCount
      let bleDials = await env.ble.connectCount
      await env.manager.checkWiFiConnectionHealth()
      #expect(env.manager.connectionRequestID == requestID)
      #expect(env.manager.wifiRecoverySlotID == recoverySlot)
      #expect(await env.wifi.connectCount == wifiDials)
      #expect(await env.ble.connectCount == bleDials)
      #expect(env.manager.connectionState == .disconnected)
    } catch {
      releaseRecovery?.resume()
      env.manager.testWiFiRecoveryEntryHandler = nil
      await env.close()
      throw error
    }
    releaseRecovery?.resume()
    env.manager.testWiFiRecoveryEntryHandler = nil
    await env.close()
  }

  enum HealthCheckExclusion: CaseIterable, Sendable {
    case recoveryInProgress
    case withoutIntent
    case bluetoothRequest
  }
}
