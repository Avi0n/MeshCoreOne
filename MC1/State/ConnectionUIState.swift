import Accessibility
import Foundation
import MC1Services

/// Manages connection-related UI state: status pills, sync activity, alerts, and pairing state.
@Observable
@MainActor
final class ConnectionUIState {
  // MARK: - Ready Toast

  /// Whether the "Ready" toast pill is visible (shown briefly after connection completes)
  private(set) var showReadyToast = false

  /// Task managing the ready toast visibility timer
  private var readyToastTask: Task<Void, Never>?

  // MARK: - Sync Failed Pill

  /// Whether the "Sync Failed" pill is visible
  private(set) var syncFailedPillVisible = false

  /// Task managing the pill visibility timer
  private var syncFailedPillTask: Task<Void, Never>?

  // MARK: - Disconnected Pill

  /// Whether the "Disconnected" pill is visible (shown after 1s delay)
  private(set) var disconnectedPillVisible = false

  /// Task managing the disconnected pill delay
  private var disconnectedPillTask: Task<Void, Never>?

  // MARK: - Sync Activity

  /// Counter for sync/settings operations (on-demand) - shows pill
  var syncActivityCount: Int = 0

  /// Current sync phase reported by SyncCoordinator callbacks.
  /// Used to defer non-essential settings reads during connect/sync.
  var currentSyncPhase: SyncPhase?

  // MARK: - Connection Alerts & Pairing

  /// Whether to show connection failure alert
  var showingConnectionFailedAlert = false

  /// Message for connection failure alert
  var connectionFailedMessage: String?

  /// Optional override for the connection-failed alert title. nil falls back
  /// to L10n.Localizable.Alert.ConnectionFailed.title ("Connection Failed").
  var connectionFailedTitle: String?

  /// Variant of the pairing-failure alert when `failedPairingDeviceID` is set.
  /// Drives action-button selection in `ContentView` so the discriminant is an
  /// explicit semantic signal rather than a "title text happens to be non-nil"
  /// heuristic. A future caller that sets a title for non-auth reasons can't
  /// silently flip the user from a non-destructive Try Again into a destructive
  /// Remove and Try Again — that mistake destroys a working bond.
  var pairingFailureKind: PairingFailureKind?

  /// Device ID that failed pairing (wrong PIN) - for recovery UI
  var failedPairingDeviceID: UUID?

  /// Device ID that triggered "connected to other app" warning - alert shown when non-nil
  var otherAppWarningDeviceID: UUID?

  /// Whether any user-initiated connection attempt is in flight — pairing
  /// (`AppState.startDeviceScan`), the transient-failure retry path
  /// (`AppState.retryFailedPairingConnect`), or simulator connect. Drives
  /// spinners and disabled buttons across pairing and retry flows. Distinct from
  /// `ConnectionManager.isPairingInProgress`, which is narrowly scoped to the
  /// `pairNewDevice` flow and is consulted by the BLE-layer reconnect gate.
  var isBusy = false

  /// Whether the device's node storage is full (set by 0x90 push, cleared on delete/overwrite)
  var isNodeStorageFull = false

  /// Task consuming `AdvertisementService.events()` for storage-full state.
  /// Re-subscribed per connection in `wireCallbacks`; cancelled on disconnect.
  private var nodeStorageEventsTask: Task<Void, Never>?

  /// Task consuming `ContactService.events()` for node-deletion events.
  /// Re-subscribed per connection in `wireCallbacks`; cancelled on disconnect.
  private var nodeDeletedEventsTask: Task<Void, Never>?

  /// Flag indicating ASK picker should be shown when app returns to foreground
  var shouldShowPickerOnForeground = false

  // MARK: - Ready Toast Methods

  /// Shows "Ready" toast pill for 2 seconds
  func showReadyToastBriefly() {
    readyToastTask?.cancel()
    showReadyToast = true

    readyToastTask = Task {
      try? await Task.sleep(for: .seconds(2))
      guard !Task.isCancelled else { return }
      showReadyToast = false
    }
  }

  /// Hides the ready toast immediately (called on disconnect)
  func hideReadyToast() {
    readyToastTask?.cancel()
    readyToastTask = nil
    showReadyToast = false
  }

  // MARK: - Sync Failed Pill Methods

  /// Shows "Sync Failed" pill for 7 seconds with VoiceOver announcement
  func showSyncFailedPill() {
    syncFailedPillTask?.cancel()
    syncFailedPillVisible = true

    announceConnectionState(L10n.Localizable.Accessibility.Connection.syncFailedDisconnecting)

    syncFailedPillTask = Task {
      try? await Task.sleep(for: .seconds(7))
      guard !Task.isCancelled else { return }
      syncFailedPillVisible = false
    }
  }

  /// Hides the sync failed pill immediately (called when resync succeeds)
  func hideSyncFailedPill() {
    syncFailedPillTask?.cancel()
    syncFailedPillTask = nil
    syncFailedPillVisible = false
  }

  // MARK: - Disconnected Pill Methods

  /// Updates disconnected pill visibility based on connection state.
  /// Called when connectionState changes or on app launch.
  func updateDisconnectedPillState(
    connectionState: MC1Services.DeviceConnectionState,
    lastConnectedDeviceID: UUID?,
    shouldSuppressDisconnectedPill: Bool
  ) {
    disconnectedPillTask?.cancel()

    guard connectionState == .disconnected,
          lastConnectedDeviceID != nil,
          !shouldSuppressDisconnectedPill else {
      disconnectedPillVisible = false
      return
    }

    disconnectedPillTask = Task {
      try? await Task.sleep(for: .seconds(1))
      guard !Task.isCancelled else { return }
      disconnectedPillVisible = true
    }
  }

  /// Hides disconnected pill immediately (called when connection starts)
  func hideDisconnectedPill() {
    disconnectedPillTask?.cancel()
    disconnectedPillTask = nil
    disconnectedPillVisible = false
  }

  // MARK: - Activity Tracking

  #if DEBUG
    /// Test helper: Simulates sync activity started callback
    func simulateSyncStarted() {
      syncActivityCount += 1
    }

    /// Test helper: Simulates sync activity ended callback (mirrors actual callback guard logic)
    func simulateSyncEnded(succeeded: Bool = false) {
      guard syncActivityCount > 0 else { return }
      syncActivityCount -= 1
      if syncActivityCount == 0, succeeded {
        showReadyToastBriefly()
      }
    }
  #endif

  // MARK: - Service Wiring

  /// Resets connection UI state when services become unavailable (disconnect).
  func handleDisconnect(
    connectionState: MC1Services.DeviceConnectionState,
    lastConnectedDeviceID: UUID?,
    shouldSuppressDisconnectedPill: Bool
  ) {
    announceConnectionState(L10n.Localizable.Accessibility.Connection.deviceConnectionLost)
    nodeStorageEventsTask?.cancel()
    nodeStorageEventsTask = nil
    nodeDeletedEventsTask?.cancel()
    nodeDeletedEventsTask = nil
    syncActivityCount = 0
    currentSyncPhase = nil
    hideReadyToast()
    isNodeStorageFull = false
    updateDisconnectedPillState(
      connectionState: connectionState,
      lastConnectedDeviceID: lastConnectedDeviceID,
      shouldSuppressDisconnectedPill: shouldSuppressDisconnectedPill
    )
  }

  /// Wires ConnectionUI-related callbacks on the sync coordinator and services.
  func wireCallbacks(
    syncCoordinator: SyncCoordinator,
    advertisementService: AdvertisementService,
    contactService: ContactService,
    connectionManager: ConnectionManager
  ) async {
    hideDisconnectedPill()

    announceConnectionState(L10n.Localizable.Accessibility.Connection.deviceReconnected)

    // Sync activity callbacks for syncing pill display
    // These are called for contacts and channels phases, NOT for messages
    await syncCoordinator.setSyncActivityCallbacks(
      onStarted: { @MainActor [weak self] in
        self?.syncActivityCount += 1
      },
      onEnded: { @MainActor [weak self] succeeded in
        guard let self else { return }
        // Guard against double-decrement: onDisconnected and sync error path
        // can both call this if WiFi drops or device switch during sync
        guard syncActivityCount > 0 else { return }
        syncActivityCount -= 1
        // Show "Ready" toast only when all sync activity completes successfully
        if syncActivityCount == 0, succeeded {
          showReadyToastBriefly()
        }
      },
      onPhaseChanged: { @MainActor [weak self] phase in
        self?.currentSyncPhase = phase
      }
    )

    // Resync failed callback for "Sync Failed" pill
    connectionManager.onResyncFailed = { [weak self] in
      self?.showSyncFailedPill()
    }

    // Node storage full events (0x90 contactsFull or 0x8F contactDeleted push).
    // Subscribed synchronously so the registration is live before
    // onConnectionEstablished can emit storage events.
    nodeStorageEventsTask?.cancel()
    let advertisementEvents = advertisementService.events()
    nodeStorageEventsTask = Task { [weak self] in
      for await event in advertisementEvents {
        guard let self else { return }
        if case let .nodeStorageFullChanged(isFull) = event {
          isNodeStorageFull = isFull
        }
      }
    }

    // Node deleted events clear the storage-full flag when the user manually
    // deletes a node. Subscribed synchronously so the registration is live
    // before a delete can emit.
    nodeDeletedEventsTask?.cancel()
    let contactEvents = contactService.events()
    nodeDeletedEventsTask = Task { [weak self] in
      for await event in contactEvents {
        guard let self else { return }
        if case .nodeDeleted = event {
          isNodeStorageFull = false
        }
      }
    }
  }

  // MARK: - Accessibility

  /// Posts a VoiceOver announcement for connection state changes
  func announceConnectionState(_ message: String) {
    AccessibilityNotification.Announcement(message).post()
  }

  // MARK: - Connection Failure Routing

  /// Routes a generic (non-pairing) connection failure. Clears
  /// `connectionFailedTitle`, `pairingFailureKind`, and `failedPairingDeviceID`
  /// so a prior `presentPairingFailure` can't leak stale state onto an unrelated
  /// failure and flip the OK-only alert into the destructive re-pair variant.
  func presentConnectionFailure(message: String?) {
    connectionFailedTitle = nil
    pairingFailureKind = nil
    failedPairingDeviceID = nil
    connectionFailedMessage = message
    showingConnectionFailedAlert = true
  }

  /// Routes a failure from a user-initiated connect to an already-paired radio.
  /// An authentication failure means the saved bond is dead, so surface the
  /// guided re-pair recovery immediately rather than an OK-only alert that
  /// leaves the responder with no way forward.
  func presentSavedDeviceConnectFailure(deviceID: UUID, error: Error) {
    switch error {
    case BLEError.deviceConnectedToOtherApp:
      otherAppWarningDeviceID = deviceID
    case BLEError.authenticationFailed:
      presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: error))
    default:
      presentConnectionFailure(message: error.userFacingMessage)
    }
  }

  /// Routes a failure from a fresh BLE pairing attempt (a device just chosen in
  /// the picker). A rejected PIN carries copy distinct from an established
  /// radio's dead bond: it names the PIN and warns that iOS will confirm
  /// removing the half-formed pairing on retry. Every other failure shares the
  /// standard pairing-failure routing.
  func presentFreshPairingFailure(_ error: PairingError) {
    guard case let .connectionFailed(deviceID, _) = error, error.isAuthenticationFailure else {
      presentPairingFailure(error)
      return
    }
    failedPairingDeviceID = deviceID
    connectionFailedTitle = L10n.Localizable.Alert.PairingFailed.title
    connectionFailedMessage = L10n.Onboarding.DeviceScan.Error.pinRejected
    pairingFailureKind = .pinRejected
    showingConnectionFailedAlert = true
  }

  /// Clears every field of the pairing-failure alert so a stale "Couldn't Pair"
  /// cannot present once a connection has succeeded.
  func clearPairingFailure() {
    showingConnectionFailedAlert = false
    connectionFailedTitle = nil
    connectionFailedMessage = nil
    pairingFailureKind = nil
    failedPairingDeviceID = nil
  }

  /// Routes a PairingError to the correct alert so every catch site produces
  /// identical UX across the three pairing-failure paths.
  func presentPairingFailure(_ error: PairingError) {
    switch error {
    case let .deviceConnectedToOtherApp(deviceID):
      // Routes through the separate "Could Not Connect" alert binding.
      otherAppWarningDeviceID = deviceID

    case let .connectionFailed(deviceID, _):
      failedPairingDeviceID = deviceID
      if error.isAuthenticationFailure {
        connectionFailedTitle = L10n.Localizable.Alert.PairingFailed.title
        connectionFailedMessage = L10n.Onboarding.DeviceScan.Error.authenticationFailed
        pairingFailureKind = .authentication
      } else {
        connectionFailedTitle = nil
        connectionFailedMessage = L10n.Onboarding.DeviceScan.Error.connectionFailed
        pairingFailureKind = .transient
      }
      showingConnectionFailedAlert = true
    }
  }
}

/// Variant of the pairing-failure alert. Determines whether the recovery action
/// is destructive (auth: must remove the bond) or non-destructive (transient:
/// keep the bond, just retry).
enum PairingFailureKind {
  /// Authentication failed — bond is bad. Recovery requires removing the bond
  /// and re-pairing.
  case authentication

  /// A fresh pairing attempt was rejected, typically a wrong PIN. Recovery
  /// requires removing the half-formed pairing before another attempt.
  case pinRejected

  /// Transient connection failure — bond is good. Recovery prefers a plain
  /// retry, with destructive remove available as a fallback.
  case transient
}
