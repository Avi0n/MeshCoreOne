import Accessibility
import Foundation
import MC1Services

/// Manages connection-related UI state: status pills, sync activity, alerts, and pairing state.
@Observable
@MainActor
public final class ConnectionUIState {

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

    /// Device ID that failed pairing (wrong PIN) - for recovery UI
    var failedPairingDeviceID: UUID?

    /// Device ID that triggered "connected to other app" warning - alert shown when non-nil
    var otherAppWarningDeviceID: UUID?

    /// Whether device pairing is in progress (ASK picker or connecting after selection)
    var isPairing = false

    /// Whether the device's node storage is full (set by 0x90 push, cleared on delete/overwrite)
    var isNodeStorageFull = false

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
        connectionState: MC1Services.ConnectionState,
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
        if syncActivityCount == 0 && succeeded {
            showReadyToastBriefly()
        }
    }
#endif

    // MARK: - Service Wiring

    /// Resets connection UI state when services become unavailable (disconnect).
    func handleDisconnect(
        connectionState: MC1Services.ConnectionState,
        lastConnectedDeviceID: UUID?,
        shouldSuppressDisconnectedPill: Bool
    ) {
        announceConnectionState(L10n.Localizable.Accessibility.Connection.deviceConnectionLost)
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
                guard self.syncActivityCount > 0 else { return }
                self.syncActivityCount -= 1
                // Show "Ready" toast only when all sync activity completes successfully
                if self.syncActivityCount == 0 && succeeded {
                    self.showReadyToastBriefly()
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

        // Node storage full callback (0x90 contactsFull or 0x8F contactDeleted push)
        await advertisementService.setNodeStorageFullChangedHandler { [weak self] isFull in
            await MainActor.run {
                self?.isNodeStorageFull = isFull
            }
        }

        // Node deleted callback (clears storage full when user manually deletes a node)
        await contactService.setNodeDeletedHandler { [weak self] in
            await MainActor.run {
                self?.isNodeStorageFull = false
            }
        }
    }

    // MARK: - Accessibility

    /// Posts a VoiceOver announcement for connection state changes
    func announceConnectionState(_ message: String) {
        AccessibilityNotification.Announcement(message).post()
    }
}
