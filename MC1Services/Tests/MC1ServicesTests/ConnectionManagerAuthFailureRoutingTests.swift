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
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // Watchdog retries keep failing the same way while the bond stays invalid;
    // the latch must keep those repeats from re-presenting the recovery alert.
    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])
  }

  @Test
  func `connection loss with a non-auth error does not fire the callback`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(
      deviceID: UUID(),
      error: BLEError.connectionFailed("link supervision timeout")
    )

    #expect(surfaced.isEmpty)
  }

  @Test
  func `connection loss with no error does not fire the callback`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(deviceID: UUID(), error: nil)

    #expect(surfaced.isEmpty)
  }

  @Test
  func `explicit disconnect clears the latch so a new failure episode re-alerts`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    await env.manager.disconnect(reason: .userInitiated)
    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)

    #expect(surfaced == [deviceID, deviceID])
  }

  @Test
  func `ready promotion clears the latch so a new failure episode re-alerts`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // A successful re-pair reconnects and promotes to .ready; install the
    // connected-state prerequisites the promotion guards check.
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session)
    env.manager.setTestState(
      connectionState: .connected,
      services: services,
      session: session,
      connectedDevice: DeviceDTO.testDevice(),
      connectionIntent: .wantsConnection()
    )
    let promoted = await env.manager.promoteToReady(
      syncSucceeded: true,
      expectedServices: services,
      transportType: .bluetooth
    )
    #expect(promoted)

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID, deviceID])
  }

  @Test
  func `clearing the surfaced-auth latch lets the same device re-alert`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID])

    // The foreground path clears the latch so a still-invalid bond re-surfaces
    // fresh from the foreground reconnect attempt.
    env.manager.clearSurfacedAuthenticationFailure()
    await env.manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)
    #expect(surfaced == [deviceID, deviceID])
  }

  // MARK: - attemptOpportunisticReconnect

  @Test
  func `opportunistic reconnect surfaces authenticationFailed thrown by connect`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    // No system pairing registry (the macOS shape), so connect(to:) reaches the
    // transport instead of failing the registry check; the transport then fails
    // auth the way a radio with an invalidated bond does. This also proves
    // connect(to:) rethrows the raw BLEError.authenticationFailed the pattern
    // match in attemptOpportunisticReconnect requires.
    env.accessorySetupKit.isSessionActive = false
    await env.transport.setConnectError(BLEError.authenticationFailed)

    await env.manager.attemptOpportunisticReconnect(deviceID: deviceID, reason: "test")

    #expect(surfaced == [deviceID])
  }

  @Test
  func `opportunistic reconnect stays silent for non-auth connect failures`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    // Default mock registry has no paired accessories, so connect(to:) throws
    // ConnectionError.deviceNotFound - a failure that must not trigger the
    // pairing-failure recovery alert.
    await env.manager.attemptOpportunisticReconnect(deviceID: UUID(), reason: "test")

    #expect(surfaced.isEmpty)
  }

  // MARK: - activate

  @Test
  func `launch auto-reconnect throwing authenticationFailed surfaces recovery without the watchdog`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    var surfaced: [UUID] = []
    env.manager.onAuthenticationFailure = { surfaced.append($0) }

    // Model a launch-time auto-reconnect to the last radio whose bond was
    // invalidated: no system pairing registry (the macOS shape) so connect(to:)
    // reaches the transport, which fails auth. The failure arrives as a thrown
    // error on the setup path, never firing onDisconnection.
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
