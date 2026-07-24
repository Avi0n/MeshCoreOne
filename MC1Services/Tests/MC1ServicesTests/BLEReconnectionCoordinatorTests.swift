import Foundation
@testable import MC1Services
import Testing

@Suite("BLEReconnectionCoordinator Tests")
@MainActor
struct BLEReconnectionCoordinatorTests {
  // MARK: - Test Helpers

  private func createCoordinator(
    delegate: MockReconnectionDelegate? = nil,
    uiTimeoutDuration: TimeInterval = 10,
    maxConnectingUIWindow: TimeInterval = 60
  ) -> (BLEReconnectionCoordinator, MockReconnectionDelegate) {
    let coordinator = BLEReconnectionCoordinator(
      uiTimeoutDuration: uiTimeoutDuration,
      maxConnectingUIWindow: maxConnectingUIWindow
    )
    let mockDelegate = delegate ?? MockReconnectionDelegate()
    coordinator.delegate = mockDelegate
    return (coordinator, mockDelegate)
  }

  // MARK: - handleEnteringAutoReconnect Tests

  @Test
  func `entering auto-reconnect sets state to .connecting when user wants connection`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.connectionState == .connecting)
  }

  @Test
  func `entering auto-reconnect tears down session`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.teardownSessionCallCount == 1)
  }

  @Test
  func `entering auto-reconnect notifies the loss before tearing down the session`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.notifyAutoReconnectStartedCallCount == 1)
    #expect(delegate.callOrder == ["notifyAutoReconnectStarted", "teardown"])
  }

  @Test
  func `entering auto-reconnect does not notify the loss when the user disconnected`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .userDisconnected
    delegate.connectionState = .disconnected

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.notifyAutoReconnectStartedCallCount == 0)
  }

  @Test
  func `entering auto-reconnect is ignored when intent is .userDisconnected`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .userDisconnected
    delegate.connectionState = .disconnected

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.connectionState == .disconnected, "State should not change when user disconnected")
    #expect(delegate.teardownSessionCallCount == 0, "Session should not be torn down")
    #expect(delegate.disconnectTransportCallCount == 1, "Transport should be disconnected")
  }

  @Test
  func `entering auto-reconnect is ignored when intent is .none`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .none
    delegate.connectionState = .disconnected

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    #expect(delegate.connectionState == .disconnected)
    #expect(delegate.disconnectTransportCallCount == 1)
  }

  // MARK: - handleReconnectionComplete Tests

  //
  // The coordinator only accepts a completion for a cycle it explicitly claimed
  // via handleEnteringAutoReconnect. Each happy-path test below claims first to
  // mirror the production wiring (setAutoReconnectingHandler always fires before
  // setReconnectionHandler for the same cycle).

  @Test
  func `reconnection complete keeps state .connecting when claim matches`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.connectionState == .connecting)
  }

  @Test
  func `reconnection complete calls rebuildSession when claim matches`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.rebuildSessionCalls.count == 1)
    #expect(delegate.rebuildSessionCalls.first == deviceID)
  }

  @Test
  func `reconnection complete is ignored when no entry was claimed`() async {
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .disconnected

    // No prior handleEnteringAutoReconnect — claim is nil. This mirrors the
    // pairing race where the entry handler was suppressed but a late completion
    // still arrives.
    await coordinator.handleReconnectionComplete(deviceID: UUID())

    #expect(delegate.connectionState == .disconnected, "Should not transition without claim")
    #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild without claim")
  }

  @Test
  func `reconnection complete is ignored when intent is .userDisconnected`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Now the user disconnects mid-reconnect.
    delegate.connectionIntent = .userDisconnected

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when user disconnected")
    #expect(delegate.disconnectTransportCallCount == 1, "Should disconnect transport")
  }

  @Test
  func `reconnection complete is ignored when already .ready`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Force state back to .ready (e.g., a parallel resync promoted the session).
    delegate.connectionState = .ready

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.connectionState == .ready, "Should not change state when already ready")
    #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when already ready")
  }

  @Test
  func `reconnection complete is ignored when .syncing (session alive, resync running)`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Simulate a parallel resync promoting the session to .syncing.
    delegate.connectionState = .syncing

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.connectionState == .syncing, "Should not change state when syncing")
    #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild when syncing")
  }

  @Test
  func `reconnection complete handles rebuild failure`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.rebuildSessionShouldThrow = true

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.handleReconnectionFailureCallCount == 1)
  }

  @Test
  func `cycle claim is held across first-fail retry gap until failure returns`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    // First rebuild fails, second (retry) also fails → handleReconnectionFailure.
    delegate.rebuildSessionThrowCount = 2

    var claimDuringFailure: UUID?
    delegate.onHandleReconnectionFailure = {
      claimDuringFailure = coordinator.reconnectingDeviceID
    }

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    // After first throw, claim must still be held (would be nil if clear ran early).
    async let completion: Void = coordinator.handleReconnectionComplete(deviceID: deviceID)
    // Yield so first rebuild starts and fails into the 2s sleep.
    await Task.yield()
    try? await Task.sleep(for: .milliseconds(50))
    #expect(coordinator.reconnectingDeviceID == deviceID)

    await completion

    #expect(delegate.handleReconnectionFailureCallCount == 1)
    #expect(claimDuringFailure == deviceID)
    #expect(coordinator.reconnectingDeviceID == nil)
  }

  @Test
  func `overlapping completions for the same device start only one rebuild`() async {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.rebuildSessionHold = true

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    async let first: Void = coordinator.handleReconnectionComplete(deviceID: deviceID)
    // Wait until first rebuild is parked inside the hold.
    for _ in 0..<100 where delegate.rebuildSessionHoldContinuation == nil {
      await Task.yield()
    }
    #expect(delegate.rebuildSessionHoldContinuation != nil)
    async let second: Void = coordinator.handleReconnectionComplete(deviceID: deviceID)
    // Release the held rebuild.
    delegate.rebuildSessionHold = false
    delegate.rebuildSessionHoldContinuation?.resume()
    await first
    await second

    #expect(delegate.rebuildSessionCalls.count == 1)
  }

  @Test
  func `already-connected completion during rebuild does not drop cycle claim`() async {
    // Production rebuildSession sets .connected early. A second completion in
    // that window must not clearActiveCycle or health single-flight is lost.
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.rebuildSessionThrowCount = 2

    var claimDuringFailure: UUID?
    delegate.onHandleReconnectionFailure = {
      claimDuringFailure = coordinator.reconnectingDeviceID
    }
    delegate.onRebuildSession = {
      // Mirror production: surface .connected during rebuild.
      delegate.connectionState = .connected
    }

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    async let first: Void = coordinator.handleReconnectionComplete(deviceID: deviceID)
    for _ in 0..<50 where delegate.rebuildSessionCalls.isEmpty {
      await Task.yield()
    }
    // Second completion while first is in flight and state is .connected.
    await coordinator.handleReconnectionComplete(deviceID: deviceID)
    #expect(coordinator.reconnectingDeviceID == deviceID)
    #expect(delegate.rebuildSessionCalls.count == 1)

    await first
    #expect(claimDuringFailure == deviceID)
    #expect(delegate.handleReconnectionFailureCallCount == 1)
  }

  @Test
  func `successful rebuild does not clear a newer cycle claim after supersession`() async {
    let deviceA = UUID()
    let deviceB = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceA)

    var enteredSuperseding = false
    delegate.onRebuildSession = {
      guard !enteredSuperseding else { return }
      enteredSuperseding = true
      // Mid-rebuild: a newer auto-reconnect cycle claims device B.
      await coordinator.handleEnteringAutoReconnect(deviceID: deviceB)
    }

    await coordinator.handleReconnectionComplete(deviceID: deviceA)

    // Newer cycle must still hold its claim; clearing on stale success would wipe it.
    #expect(coordinator.reconnectingDeviceID == deviceB)
  }

  @Test
  func `stale device completion does not cancel active timeout`() async throws {
    let activeDevice = UUID()
    let staleDevice = UUID()
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: activeDevice)
    #expect(delegate.connectionState == .connecting)

    // Stale completion for a different device should be rejected
    await coordinator.handleReconnectionComplete(deviceID: staleDevice)

    // Timeout should still fire because it was not canceled
    try await waitUntil("Timeout should still fire after stale completion") {
      delegate.connectionState == .disconnected
    }

    #expect(delegate.connectionState == .disconnected, "Timeout should still fire after stale completion")
    #expect(delegate.rebuildSessionCalls.isEmpty, "Should not rebuild for stale device")
  }

  // MARK: - UI Timeout Tests

  @Test
  func `UI timeout transitions to disconnected after duration`() async throws {
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())
    #expect(delegate.connectionState == .connecting)

    // Wait for timeout to fire and transition state
    try await waitUntil("Timeout should transition to disconnected") {
      delegate.connectionState == .disconnected
    }

    #expect(delegate.connectionState == .disconnected)
    #expect(delegate.connectedDeviceWasCleared == true)
    #expect(delegate.notifyConnectionLostCallCount == 1)
  }

  @Test
  func `UI timeout is cancelled when reconnection completes`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    #expect(delegate.connectionState == .connecting)

    // Complete reconnection before timeout (same device)
    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    // Fixed sleep: negative assertion — confirm timeout did NOT fire
    try await Task.sleep(for: .milliseconds(250))

    // Should be .connecting from reconnection complete, not .disconnected from timeout
    #expect(delegate.connectionState == .connecting)
    #expect(delegate.notifyConnectionLostCallCount == 0)
  }

  // MARK: - Stale Retry Tests

  @Test
  func `stale rebuild retry is aborted when new reconnect cycle starts during delay`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator()
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.rebuildSessionShouldThrow = true

    // Claim the first cycle so the completion is accepted.
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Start first reconnection — rebuild will fail, triggering 2s retry delay
    let firstReconnectTask = Task {
      await coordinator.handleReconnectionComplete(deviceID: deviceID)
    }

    // Wait for first rebuild to fail and enter the 2s retry delay
    try await waitUntil("First rebuild should have been attempted") {
      delegate.rebuildSessionCalls.count == 1
    }
    #expect(delegate.rebuildSessionCalls.count == 1, "First rebuild should have been attempted")

    // Start a new reconnect cycle during the delay — this bumps the generation counter.
    // Re-claim the cycle since the prior completion cleared reconnectingDeviceID.
    delegate.rebuildSessionShouldThrow = false
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    // Wait for the first task's stale retry to wake and be aborted
    await firstReconnectTask.value

    // Should have exactly 2 rebuild calls: first (failed) + new cycle (succeeded).
    // The stale retry should have been aborted by the generation check.
    #expect(delegate.rebuildSessionCalls.count == 2, "Stale retry should have been aborted")
    #expect(delegate.handleReconnectionFailureCallCount == 0, "No failure handler since new cycle succeeded")
  }

  // MARK: - Max Connecting Window Tests

  @Test
  func `UI timeout disconnects when max connecting window exceeded`() async throws {
    let (coordinator, delegate) = createCoordinator(
      uiTimeoutDuration: 0.05,
      maxConnectingUIWindow: 0.15
    )
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    // Wait for max connecting window to expire and disconnect
    try await waitUntil("Max connecting window should trigger disconnect") {
      delegate.connectionState == .disconnected
    }

    #expect(delegate.connectionState == .disconnected)
    #expect(delegate.notifyConnectionLostCallCount == 1)
  }

  @Test
  func `same-device completion is accepted after UI timeout while transport auto-reconnects`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator(
      uiTimeoutDuration: 0.05,
      maxConnectingUIWindow: 0.15
    )
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    try await waitUntil("UI timeout should transition presentation to disconnected") {
      delegate.connectionState == .disconnected
    }

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.rebuildSessionCalls == [deviceID])
    #expect(delegate.connectionState == .connecting)
  }

  @Test
  func `UI timeout clears cycle when transport stops auto-reconnecting`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.05)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = false

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    try await waitUntil("UI timeout should transition to disconnected") {
      delegate.connectionState == .disconnected
    }

    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.rebuildSessionCalls.isEmpty)
  }

  @Test
  func `different-device completion is rejected after UI timeout`() async throws {
    let activeDeviceID = UUID()
    let staleDeviceID = UUID()
    let (coordinator, delegate) = createCoordinator(
      uiTimeoutDuration: 0.05,
      maxConnectingUIWindow: 0.15
    )
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    await coordinator.handleEnteringAutoReconnect(deviceID: activeDeviceID)

    try await waitUntil("UI timeout should transition presentation to disconnected") {
      delegate.connectionState == .disconnected
    }

    await coordinator.handleReconnectionComplete(deviceID: staleDeviceID)

    #expect(delegate.rebuildSessionCalls.isEmpty)
    #expect(delegate.connectionState == .disconnected)
  }

  @Test
  func `user-disconnected completion after UI timeout remains rejected`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator(
      uiTimeoutDuration: 0.05,
      maxConnectingUIWindow: 0.15
    )
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    try await waitUntil("UI timeout should transition presentation to disconnected") {
      delegate.connectionState == .disconnected
    }

    delegate.connectionIntent = .userDisconnected
    await coordinator.handleReconnectionComplete(deviceID: deviceID)

    #expect(delegate.rebuildSessionCalls.isEmpty)
    #expect(delegate.disconnectTransportCallCount == 1)
  }

  // MARK: - cancelTimeout Tests

  @Test
  func `UI timeout re-arms if BLE is still auto-reconnecting`() async throws {
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    let deviceID = UUID()
    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Fixed sleep: negative assertion — confirm re-arm keeps state as .connecting
    try await Task.sleep(for: .milliseconds(200))

    // Should still be .connecting because BLE is auto-reconnecting
    #expect(delegate.connectionState == .connecting)
    #expect(delegate.notifyConnectionLostCallCount == 0)
  }

  @Test
  func `UI timeout eventually disconnects when max window exceeded`() async throws {
    // Use a very short maxConnectingUIWindow via a coordinator with short timeout
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.05)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = true

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    // Fixed sleep: negative assertion — within the 60s max window, re-arm
    // should keep the state as .connecting. We can't wait 60s in a test, so
    // verify the re-arm mechanism works within a short window.
    try await Task.sleep(for: .milliseconds(200))

    // Within the 60s window, should still be .connecting
    #expect(delegate.connectionState == .connecting)
  }

  @Test
  func `UI timeout fires normally when BLE is not auto-reconnecting`() async throws {
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = false

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())

    try await waitUntil("Timeout should fire when BLE is not auto-reconnecting") {
      delegate.connectionState == .disconnected
    }

    #expect(delegate.connectionState == .disconnected)
    #expect(delegate.notifyConnectionLostCallCount == 1)
  }

  @Test
  func `UI timeout aborts when reconnection completes during its transport query`() async throws {
    let deviceID = UUID()
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.05)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready
    delegate.stubbedBLEPhaseIsAutoReconnecting = false

    await coordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    // Run handleReconnectionComplete while the timeout body is suspended on
    // isTransportAutoReconnecting(), the reentrancy window where a stale
    // timeout could clobber the freshly completed reconnection.
    delegate.onIsTransportAutoReconnecting = { [weak coordinator, weak delegate] in
      delegate?.onIsTransportAutoReconnecting = nil
      await coordinator?.handleReconnectionComplete(deviceID: deviceID)
    }

    try await waitUntil("completion should run during the timeout suspension") {
      delegate.rebuildSessionCalls == [deviceID]
    }

    // Fixed sleep: negative assertion: give the resumed timeout body a
    // chance to (incorrectly) force disconnected state.
    try await Task.sleep(for: .milliseconds(150))

    #expect(delegate.connectionState == .connecting, "Stale timeout must not clobber the completed reconnection")
    #expect(delegate.notifyConnectionLostCallCount == 0)
    #expect(delegate.connectedDeviceWasCleared == false)
  }

  @Test
  func `cancelTimeout prevents timeout from firing`() async throws {
    let (coordinator, delegate) = createCoordinator(uiTimeoutDuration: 0.1)
    delegate.connectionIntent = .wantsConnection()
    delegate.connectionState = .ready

    await coordinator.handleEnteringAutoReconnect(deviceID: UUID())
    coordinator.cancelTimeout()

    // Fixed sleep: negative assertion — confirm timeout was cancelled
    try await Task.sleep(for: .milliseconds(250))

    // State should remain .connecting (timeout was cancelled)
    #expect(delegate.connectionState == .connecting)
  }
}

// MARK: - Mock Delegate

@MainActor
private final class MockReconnectionDelegate: BLEReconnectionDelegate {
  var connectionIntent: ConnectionIntent = .none
  var connectionState: DeviceConnectionState = .disconnected

  var teardownSessionCallCount = 0
  var rebuildSessionCalls: [UUID] = []
  var rebuildSessionShouldThrow = false
  /// When > 0, the next N rebuildSession calls throw then decrement.
  var rebuildSessionThrowCount = 0
  /// When true, rebuildSession parks until `rebuildSessionHold` is set false
  /// and the continuation is resumed.
  var rebuildSessionHold = false
  var rebuildSessionHoldContinuation: CheckedContinuation<Void, Never>?
  var disconnectTransportCallCount = 0
  var notifyConnectionLostCallCount = 0
  var notifyAutoReconnectStartedCallCount = 0
  var handleReconnectionFailureCallCount = 0
  /// Records delegate calls in order so tests can assert the auto-reconnect
  /// notification lands before session teardown.
  private(set) var callOrder: [String] = []
  var connectedDeviceWasCleared = false
  var stubbedBLEPhaseIsAutoReconnecting = false
  /// Runs inside `isTransportAutoReconnecting()` so tests can interleave work
  /// at that suspension point before the stubbed value is returned.
  var onIsTransportAutoReconnecting: (@MainActor () async -> Void)?
  var onHandleReconnectionFailure: (@MainActor () async -> Void)?
  var onRebuildSession: (@MainActor () async -> Void)?

  func setConnectionState(_ state: DeviceConnectionState) {
    connectionState = state
  }

  func setConnectedDevice(_ device: DeviceDTO?) {
    if device == nil {
      connectedDeviceWasCleared = true
    }
  }

  func teardownSessionForReconnect() async {
    teardownSessionCallCount += 1
    callOrder.append("teardown")
  }

  func notifyAutoReconnectStarted() async {
    notifyAutoReconnectStartedCallCount += 1
    callOrder.append("notifyAutoReconnectStarted")
  }

  func rebuildSession(deviceID: UUID) async throws {
    rebuildSessionCalls.append(deviceID)
    if let onRebuildSession {
      await onRebuildSession()
    }
    if rebuildSessionHold {
      await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
        rebuildSessionHoldContinuation = cont
      }
    }
    if rebuildSessionThrowCount > 0 {
      rebuildSessionThrowCount -= 1
      throw ReconnectionTestError.rebuildFailed
    }
    if rebuildSessionShouldThrow {
      throw ReconnectionTestError.rebuildFailed
    }
  }

  func disconnectTransport() async {
    disconnectTransportCallCount += 1
  }

  func notifyConnectionLost() async {
    notifyConnectionLostCallCount += 1
  }

  func handleReconnectionFailure() async {
    handleReconnectionFailureCallCount += 1
    if let onHandleReconnectionFailure {
      await onHandleReconnectionFailure()
    }
  }

  func isTransportAutoReconnecting() async -> Bool {
    if let hook = onIsTransportAutoReconnecting {
      await hook()
    }
    return stubbedBLEPhaseIsAutoReconnecting
  }
}

private enum ReconnectionTestError: Error {
  case rebuildFailed
}
