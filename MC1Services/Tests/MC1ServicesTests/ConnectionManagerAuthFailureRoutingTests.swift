import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

/// Tests for `onAuthenticationFailure` routing: both producer paths
/// (`handleConnectionLoss` and `attemptOpportunisticReconnect`) must surface
/// `BLEError.authenticationFailed` through the callback, exactly once per
/// failure episode, and must stay silent for every other error.
@Suite("ConnectionManager Auth Failure Routing Tests")
@MainActor
struct ConnectionManagerAuthFailureRoutingTests {
  // MARK: - handleConnectionLoss

  @Test
  func `connection loss with authenticationFailed fires the callback once per episode`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // Watchdog retries keep failing the same way while the bond stays invalid;
    // the latch must keep those repeats from re-presenting the recovery alert.
    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])
  }

  @Test
  func `connection loss with a non-auth error does not fire the callback`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(
      deviceID: UUID(),
      error: BLEError.connectionFailed("link supervision timeout")
    )

    #expect(surfaced.isEmpty)
  }

  @Test
  func `connection loss with no error does not fire the callback`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(deviceID: UUID(), error: nil)

    #expect(surfaced.isEmpty)
  }

  @Test
  func `explicit disconnect clears the latch so a new failure episode re-alerts`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    await env.manager.disconnect(reason: .userInitiated)
    env.manager.setTestState(connectionIntent: .wantsConnection())
    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)

    #expect(surfaced == [deviceID, deviceID])
  }

  @Test
  func `ready promotion clears the latch so a new failure episode re-alerts`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // A successful re-pair reconnects and promotes to .ready; install the
    // connected-state prerequisites the promotion guards check.
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session)
    env.manager.setTestState(
      connectionState: .connected,
      services: services,
      session: session,
      connectedDevice: DeviceDTO.testDevice(id: deviceID),
      connectionIntent: .wantsConnection()
    )
    let link = try #require(env.manager.installedLink)
    let promoted = await env.manager.completeSync(
      link, syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean)
    )
    #expect(promoted)

    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID, deviceID])
  }

  @Test
  func `clearing the surfaced-auth latch lets the same device re-alert`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // The foreground path clears the latch so a still-invalid bond re-surfaces
    // fresh from the foreground reconnect attempt.
    env.manager.clearSurfacedAuthenticationFailure()
    await env.manager.acceptSeededBLELoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID, deviceID])
  }

  // MARK: - attemptOpportunisticReconnect

  @Test
  func `opportunistic reconnect surfaces authenticationFailed thrown by connect`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    env.manager.testLastConnectedDeviceID = deviceID
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    // An eligible saved target reaches the transport before session startup.
    env.accessorySetupKit.isSessionActive = false
    await env.transport.setConnectError(BLEError.authenticationFailed)

    await env.manager.attemptOpportunisticReconnect(deviceID: deviceID, reason: "test")

    let dialedIDs = await env.transport.connectInvocations.map(\.deviceID)
    #expect(dialedIDs == [deviceID])
    #expect(env.manager.desiredRequest?.target == .bluetooth(deviceID))
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.session == nil)
    #expect(surfaced == [deviceID])
    _ = await env.manager.disconnect(reason: .userInitiated)
  }

  @Test
  func `opportunistic reconnect stays silent for non-auth connect failures`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    env.manager.testLastConnectedDeviceID = deviceID
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    env.accessorySetupKit.isSessionActive = false
    await env.transport.setConnectError(BLEError.bluetoothUnavailable)
    await env.manager.attemptOpportunisticReconnect(deviceID: deviceID, reason: "test")

    let dialedIDs = await env.transport.connectInvocations.map(\.deviceID)
    #expect(dialedIDs == [deviceID])
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.session == nil)
    #expect(surfaced.isEmpty)
    _ = await env.manager.disconnect(reason: .userInitiated)
  }

  @Test
  func `opportunistic reconnect without an authoritative target does not dial or surface auth`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }
    env.accessorySetupKit.isSessionActive = false
    await env.transport.setConnectError(BLEError.authenticationFailed)

    #expect(env.manager.connectionRequestID == nil)
    #expect(env.manager.lastConnectedDeviceID == nil)
    await env.manager.attemptOpportunisticReconnect(deviceID: UUID(), reason: "test")

    #expect(await env.transport.connectInvocations.isEmpty)
    #expect(env.manager.connectionRequestID == nil)
    #expect(surfaced.isEmpty)
  }

  // MARK: - activate

  @Test
  func `launch auto-reconnect throwing authenticationFailed surfaces recovery without the watchdog`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.setTestState(connectionIntent: .wantsConnection())
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    // Without a system pairing registry, launch-time recovery reaches a transport
    // that throws authentication failure before it can emit a lifecycle event.
    env.manager.testLastConnectedDeviceID = deviceID
    env.accessorySetupKit.isSessionActive = false
    await env.transport.setConnectError(BLEError.authenticationFailed)

    await env.manager.activate()

    // Recovery is surfaced synchronously from the launch catch, not on the
    // watchdog's first delayed tick, even though the watchdog is armed as the
    // background retry fallback.
    #expect(surfaced == [deviceID])
    #expect(env.manager.isReconnectionWatchdogRunning)

    env.manager.stopReconnectionWatchdog()
  }
}
