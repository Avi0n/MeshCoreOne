// BLEReconnectionCoordinator.swift

import OSLog

/// Coordinates the iOS auto-reconnect lifecycle, managing timeout state and
/// orchestrating teardown/rebuild via its delegate.
///
/// Extracted from ConnectionManager to isolate the reconnect timeout/state machine
/// from session rebuild logic.
@MainActor
final class BLEReconnectionCoordinator {
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "BLEReconnectionCoordinator")

  weak var delegate: BLEReconnectionDelegate?

  /// The device ID for the reconnect cycle currently claimed by
  /// `handleEnteringAutoReconnect`. Completions are accepted only when this matches
  /// the completing device. A nil value (entry suppressed, or manually superseded)
  /// rejects late completions to prevent them from racing a new flow.
  var reconnectingDeviceID: UUID? {
    activeCycle?.deviceID
  }

  private struct ReconnectCycle {
    let deviceID: UUID
    let generation: Int
    var uiTimedOut: Bool
  }

  private var activeCycle: ReconnectCycle?

  private var timeoutTask: Task<Void, Never>?

  /// Generation of an in-flight `rebuildSession` (including first-fail retry), or
  /// nil when none. Rejects a second completion only for the same generation so
  /// a newer cycle (bumped by `handleEnteringAutoReconnect`) can still rebuild
  /// while a stale retry sleeps, without allowing dual entry on one generation.
  private var sessionRebuildInFlightGeneration: Int?

  /// Incremented each time a reconnection cycle starts, used to detect stale rebuilds and retries.
  private(set) var reconnectGeneration = 0

  /// UI timeout duration before transitioning from "connecting" to "disconnected".
  /// iOS auto-reconnect continues in the background even after this fires.
  private let uiTimeoutDuration: TimeInterval

  /// When the current reconnect UI window started. Used to bound re-arms.
  private var reconnectUIWindowStart: Date?

  /// Maximum time the UI can stay in `.connecting` state, even if BLE is still
  /// auto-reconnecting. Prevents indefinite connecting UI when transport is stuck.
  private let maxConnectingUIWindow: TimeInterval

  init(uiTimeoutDuration: TimeInterval = 15, maxConnectingUIWindow: TimeInterval = 60) {
    self.uiTimeoutDuration = uiTimeoutDuration
    self.maxConnectingUIWindow = maxConnectingUIWindow
  }

  /// Handles the device entering iOS auto-reconnect phase.
  /// Tears down session layer and starts a UI timeout.
  func handleEnteringAutoReconnect(deviceID: UUID) async {
    guard let delegate else { return }
    logger.info("[BLE] handleEnteringAutoReconnect: device=\(deviceID.uuidString.prefix(8)), connectionState=\(String(describing: delegate.connectionState))")

    guard delegate.connectionIntent.wantsConnection else {
      logger.info("Ignoring auto-reconnect: user disconnected")
      await delegate.disconnectTransport()
      return
    }

    // C3 fix: set connecting state BEFORE awaiting teardown so that
    // handleReconnectionComplete() sees .connecting even if it runs
    // during the teardown await.
    delegate.setConnectionState(.connecting)
    reconnectGeneration += 1
    activeCycle = ReconnectCycle(deviceID: deviceID, generation: reconnectGeneration, uiTimedOut: false)
    reconnectUIWindowStart = Date()

    // Reflect the drop before teardown so the Live Activity write runs at the
    // front of the bounded disconnect wake window, not behind session teardown.
    // The reconnect cycle is already claimed, so this can't strand a completion.
    await delegate.notifyAutoReconnectStarted()

    // Tear down session layer (it's invalid now)
    await delegate.teardownSessionForReconnect()

    // Start UI timeout
    logger.info("[BLE] Arming UI timeout: \(uiTimeoutDuration)s for device \(deviceID.uuidString.prefix(8))")
    armTimeout(deviceID: deviceID, generation: reconnectGeneration)
  }

  /// Handles iOS auto-reconnect completion. Cancels the UI timeout
  /// and delegates session rebuild to ConnectionManager.
  func handleReconnectionComplete(deviceID: UUID) async {
    guard let delegate else { return }
    logger.info("[BLE] handleReconnectionComplete: device=\(deviceID.uuidString.prefix(8))")

    guard delegate.connectionIntent.wantsConnection else {
      cancelTimeout()
      logger.info("Ignoring reconnection: user disconnected")
      clearActiveCycle(invalidateGeneration: true)
      await delegate.disconnectTransport()
      return
    }

    // Reject completions for cycles we didn't claim — an orphaned completion
    // would race the new flow. Don't cancel the active timeout — the current
    // reconnect retains its fallback.
    guard activeCycle?.deviceID == deviceID else {
      let claim = reconnectingDeviceID?.uuidString.prefix(8) ?? "no claim"
      logger.warning("[BLE] Ignoring auto-reconnect completion for \(deviceID.uuidString.prefix(8)): \(claim)")
      return
    }

    let completedAfterUITimeout = activeCycle?.uiTimedOut == true
    if completedAfterUITimeout {
      logger.info("[BLE] Accepting auto-reconnect completion after UI timeout for \(deviceID.uuidString.prefix(8))")
    }

    // This completion is for our device — safe to cancel timeout
    cancelTimeout()
    // Keep the cycle claimed across rebuild, the first-fail → retry sleep, and
    // handleReconnectionFailure so concurrent checkBLEConnectionHealth cannot
    // install a stack that the failure handler would tear down.

    // Accept both disconnected (normal) and connecting (auto-reconnect in progress).
    // Production rebuildSession sets `.connected` early, so a second same-device
    // completion often arrives while state is already `.connected`. If a rebuild
    // (or its retry/failure path) is in flight, ignore without clearing the cycle
    // claim — that claim is what keeps health single-flight during the gap.
    let state = delegate.connectionState
    guard state == .disconnected || state == .connecting else {
      if sessionRebuildInFlightGeneration != nil {
        logger.info(
          "[BLE] Ignoring reconnection for \(deviceID.uuidString.prefix(8)): already \(String(describing: state)) while rebuild in flight"
        )
        return
      }
      logger.info("Ignoring reconnection: already \(String(describing: state))")
      clearActiveCycle(invalidateGeneration: false)
      return
    }

    // Reject a second completion while a rebuild for *this* generation is live
    // (dual didConnect). Compare against reconnectGeneration *before* bumping so
    // the first completion's in-flight mark matches. A newer
    // handleEnteringAutoReconnect bumps generation first, so a completion after
    // that can rebuild while a stale retry still sleeps under an older mark.
    if sessionRebuildInFlightGeneration == reconnectGeneration {
      logger.warning(
        "[BLE] Ignoring auto-reconnect completion for \(deviceID.uuidString.prefix(8)): session rebuild already in flight for generation \(reconnectGeneration)"
      )
      return
    }

    reconnectGeneration += 1
    let expectedGeneration = reconnectGeneration

    delegate.setConnectionState(.connecting)
    sessionRebuildInFlightGeneration = expectedGeneration
    defer {
      if sessionRebuildInFlightGeneration == expectedGeneration {
        sessionRebuildInFlightGeneration = nil
      }
    }

    do {
      try await delegate.rebuildSession(deviceID: deviceID)
      // Only clear if we still own the generation — a non-throwing supersession
      // abort leaves a newer cycle's claim intact.
      if expectedGeneration == reconnectGeneration {
        clearActiveCycle(invalidateGeneration: false)
      }
    } catch {
      logger.warning("[BLE] Auto-reconnect session rebuild failed: \(error.localizedDescription) - retrying in 2s")
      await retryRebuild(deviceID: deviceID, expectedGeneration: expectedGeneration)
    }
  }

  /// Restarts the UI timeout without tearing down the session.
  /// Used when user taps Connect while iOS auto-reconnect is already in progress.
  func restartTimeout(deviceID: UUID) {
    if activeCycle?.deviceID != deviceID {
      reconnectGeneration += 1
      activeCycle = ReconnectCycle(deviceID: deviceID, generation: reconnectGeneration, uiTimedOut: false)
    } else {
      activeCycle?.uiTimedOut = false
    }
    reconnectUIWindowStart = Date()
    guard let generation = activeCycle?.generation else { return }
    armTimeout(deviceID: deviceID, generation: generation)
  }

  private func armTimeout(deviceID: UUID, generation: Int) {
    cancelTimeout()
    timeoutTask = Task { [weak self, uiTimeoutDuration] in
      try? await Task.sleep(for: .seconds(uiTimeoutDuration))
      guard !Task.isCancelled, let self else { return }
      await handleUITimeout(deviceID: deviceID, generation: generation)
    }
  }

  /// Cancels the UI timeout timer.
  func cancelTimeout() {
    if timeoutTask != nil {
      logger.debug("[BLE] Cancelling UI timeout")
    }
    timeoutTask?.cancel()
    timeoutTask = nil
  }

  /// Clears the reconnecting device ID, used when manual connect supersedes auto-reconnect.
  func clearReconnectingDevice() {
    clearActiveCycle(invalidateGeneration: true)
  }

  private func clearActiveCycle(invalidateGeneration: Bool) {
    guard activeCycle != nil else { return }
    activeCycle = nil
    reconnectUIWindowStart = nil
    if invalidateGeneration {
      reconnectGeneration += 1
    }
  }

  /// Retries a failed session rebuild after a short delay, aborting if the reconnect
  /// generation has changed or the user disconnected during the wait.
  private func retryRebuild(deviceID: UUID, expectedGeneration: Int) async {
    guard let delegate else {
      if expectedGeneration == reconnectGeneration {
        clearActiveCycle(invalidateGeneration: false)
      }
      return
    }

    try? await Task.sleep(for: .seconds(2))

    guard expectedGeneration == reconnectGeneration else {
      logger.info("New reconnect cycle started during rebuild retry delay - aborting stale retry")
      // A newer cycle owns the claim; do not clear theirs.
      return
    }
    guard delegate.connectionIntent.wantsConnection else {
      logger.info("User disconnected during rebuild retry delay")
      // Hold claim through failure handling so concurrent health cannot rebuild
      // under a torn-down stack, then release only if we still own the generation.
      await delegate.handleReconnectionFailure()
      if expectedGeneration == reconnectGeneration {
        clearActiveCycle(invalidateGeneration: false)
      }
      return
    }

    do {
      try await delegate.rebuildSession(deviceID: deviceID)
      logger.info("[BLE] Auto-reconnect session rebuild succeeded on retry")
      if expectedGeneration == reconnectGeneration {
        clearActiveCycle(invalidateGeneration: false)
      }
    } catch {
      logger.error("[BLE] Auto-reconnect session rebuild failed on retry: \(error.localizedDescription)")
      await delegate.handleReconnectionFailure()
      if expectedGeneration == reconnectGeneration {
        clearActiveCycle(invalidateGeneration: false)
      }
    }
  }

  private func handleUITimeout(deviceID: UUID, generation: Int) async {
    guard let delegate, delegate.connectionState == .connecting else { return }
    guard let activeCycle,
          activeCycle.deviceID == deviceID,
          activeCycle.generation == generation else { return }
    let elapsed = Date().timeIntervalSince(reconnectUIWindowStart ?? Date())
    logger.info("[BLE] handleUITimeout: device=\(deviceID.uuidString.prefix(8)), elapsed=\(elapsed.formatted(.number.precision(.fractionLength(1))))s")

    // If BLE transport is still actively auto-reconnecting and we haven't
    // exceeded the max connecting window, re-arm the timeout instead of
    // forcing disconnected state. This handles the case where the timeout
    // was armed before suspension and fires immediately on resume.
    let transportAutoReconnecting = await delegate.isTransportAutoReconnecting()

    // Re-validate after the await: handleReconnectionComplete can run to
    // completion during the suspension, and this timeout must not force
    // a freshly rebuilt session back to disconnected.
    guard delegate.connectionState == .connecting,
          let currentCycle = self.activeCycle,
          currentCycle.deviceID == deviceID,
          currentCycle.generation == generation else { return }

    if transportAutoReconnecting, elapsed < maxConnectingUIWindow {
      logger.info("[BLE] UI timeout fired but BLE still auto-reconnecting, re-arming (elapsed: \(elapsed.formatted(.number.precision(.fractionLength(1))))s)")
      armTimeout(deviceID: deviceID, generation: generation)
      return
    }

    if transportAutoReconnecting {
      self.activeCycle?.uiTimedOut = true
    } else {
      clearActiveCycle(invalidateGeneration: true)
    }
    logger.warning(
      "[BLE] Auto-reconnect UI timeout (\(uiTimeoutDuration)s) fired - transitioning UI to disconnected (iOS reconnect continues in background)"
    )
    delegate.setConnectionState(.disconnected)
    delegate.setConnectedDevice(nil)
    await delegate.notifyConnectionLost()
  }
}

/// Delegate protocol for BLEReconnectionCoordinator.
/// ConnectionManager implements this to provide session management.
@MainActor
protocol BLEReconnectionDelegate: AnyObject {
  var connectionIntent: ConnectionIntent { get }
  var connectionState: DeviceConnectionState { get }

  /// Sets the connection state (used by coordinator for state transitions).
  func setConnectionState(_ state: DeviceConnectionState)

  /// Sets the connected device (used by coordinator to clear on timeout).
  func setConnectedDevice(_ device: DeviceDTO?)

  /// Tears down the current session and services for reconnection.
  func teardownSessionForReconnect() async

  /// Rebuilds the session after iOS auto-reconnect completes.
  func rebuildSession(deviceID: UUID) async throws

  /// Disconnects the BLE transport (used when user disconnected during reconnect).
  func disconnectTransport() async

  /// Notifies the UI layer that the link dropped and iOS auto-reconnect has begun,
  /// after the cycle is claimed and before session teardown.
  func notifyAutoReconnectStarted() async

  /// Notifies the UI layer of connection loss.
  func notifyConnectionLost() async

  /// Handles reconnection failure: app-stack teardown with preserve-vs-sever
  /// branching on link health, intent, and rebuild budget. Does not always
  /// disconnect the transport — a live link under budget is preserved for
  /// in-place health-check rebuild.
  func handleReconnectionFailure() async

  /// Returns whether the BLE transport is currently in auto-reconnecting phase.
  func isTransportAutoReconnecting() async -> Bool
}
