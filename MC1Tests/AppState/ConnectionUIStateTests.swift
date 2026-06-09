import Testing
import Foundation
@testable import MC1
@testable import MC1Services

@Suite("Connection UI State Tests")
@MainActor
struct ConnectionUIStateTests {

    // MARK: - statusPillState Priority

    @Test("statusPillState is hidden by default")
    func hiddenByDefault() {
        let appState = AppState()
        #expect(appState.statusPillState == .hidden)
    }

    @Test("Failed state takes priority over syncing")
    func failedOverSyncing() {
        let appState = AppState()
        appState.connectionUI.simulateSyncStarted()
        appState.connectionUI.showSyncFailedPill()

        #expect(appState.statusPillState == .failed(message: "Sync Failed"))
    }

    @Test("Syncing takes priority over ready toast")
    func syncingOverReady() {
        let appState = AppState()
        appState.connectionUI.showReadyToastBriefly()
        appState.connectionUI.simulateSyncStarted()

        #expect(appState.statusPillState == .syncing)
    }

    @Test("Ready toast takes priority over disconnected")
    func readyOverDisconnected() {
        let appState = AppState()
        appState.connectionUI.showReadyToastBriefly()

        // Even if disconnectedPillVisible were true, ready should win
        #expect(appState.statusPillState == .ready)
    }

    @Test("Multiple sync activities keep syncing state until all end")
    func multipleSyncActivities() {
        let appState = AppState()

        appState.connectionUI.simulateSyncStarted()
        appState.connectionUI.simulateSyncStarted()
        #expect(appState.statusPillState == .syncing)

        appState.connectionUI.simulateSyncEnded()
        #expect(appState.statusPillState == .syncing)

        appState.connectionUI.simulateSyncEnded()
        // After all sync activity ends, should not be syncing
        #expect(appState.statusPillState != .syncing)
    }

    // MARK: - Ready Toast

    @Test("showReadyToastBriefly sets showReadyToast to true")
    func showReadyToast() {
        let appState = AppState()

        appState.connectionUI.showReadyToastBriefly()

        #expect(appState.connectionUI.showReadyToast == true)
        #expect(appState.statusPillState == .ready)
    }

    @Test("hideReadyToast immediately clears toast")
    func hideReadyToast() {
        let appState = AppState()
        appState.connectionUI.showReadyToastBriefly()
        #expect(appState.connectionUI.showReadyToast == true)

        appState.connectionUI.hideReadyToast()

        #expect(appState.connectionUI.showReadyToast == false)
        #expect(appState.statusPillState == .hidden)
    }

    @Test("showReadyToastBriefly auto-hides after delay")
    func readyToastAutoHides() async throws {
        let appState = AppState()

        appState.connectionUI.showReadyToastBriefly()
        #expect(appState.connectionUI.showReadyToast == true)

        // Wait for the 2-second auto-hide plus margin
        try await Task.sleep(for: .seconds(2.3))

        #expect(appState.connectionUI.showReadyToast == false)
    }

    @Test("Calling showReadyToastBriefly again resets the timer")
    func readyToastTimerReset() async throws {
        let appState = AppState()

        appState.connectionUI.showReadyToastBriefly()
        try await Task.sleep(for: .seconds(1.5))

        // Call again to reset
        appState.connectionUI.showReadyToastBriefly()
        #expect(appState.connectionUI.showReadyToast == true)

        // Wait past original timer but within new timer
        try await Task.sleep(for: .seconds(1.0))
        #expect(appState.connectionUI.showReadyToast == true)
    }

    // MARK: - Sync Failed Pill

    @Test("showSyncFailedPill sets visible flag")
    func showSyncFailedPill() {
        let appState = AppState()

        appState.connectionUI.showSyncFailedPill()

        #expect(appState.connectionUI.syncFailedPillVisible == true)
        #expect(appState.statusPillState == .failed(message: "Sync Failed"))
    }

    @Test("hideSyncFailedPill immediately clears pill")
    func hideSyncFailedPill() {
        let appState = AppState()
        appState.connectionUI.showSyncFailedPill()

        appState.connectionUI.hideSyncFailedPill()

        #expect(appState.connectionUI.syncFailedPillVisible == false)
    }

    @Test("showSyncFailedPill auto-hides after delay")
    func syncFailedPillAutoHides() async throws {
        let appState = AppState()

        appState.connectionUI.showSyncFailedPill()
        #expect(appState.connectionUI.syncFailedPillVisible == true)

        // Wait for the 7-second auto-hide plus margin
        try await Task.sleep(for: .seconds(7.3))

        #expect(appState.connectionUI.syncFailedPillVisible == false)
    }

    // MARK: - Disconnected Pill

    @Test("disconnectedPillVisible is false by default")
    func disconnectedPillDefault() {
        let appState = AppState()
        #expect(appState.connectionUI.disconnectedPillVisible == false)
    }

    @Test("hideDisconnectedPill clears pill immediately")
    func hideDisconnectedPill() {
        let appState = AppState()

        appState.connectionUI.hideDisconnectedPill()

        #expect(appState.connectionUI.disconnectedPillVisible == false)
    }

    @Test("updateDisconnectedPillState without paired device stays hidden")
    func disconnectedPillNoPairedDevice() async throws {
        let appState = AppState()

        appState.connectionUI.updateDisconnectedPillState(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        try await Task.sleep(for: .seconds(1.3))
        #expect(appState.connectionUI.disconnectedPillVisible == false)
    }

    // MARK: - canRunSettingsStartupReads

    @Test("canRunSettingsStartupReads is false when disconnected")
    func cannotRunSettingsWhenDisconnected() {
        let appState = AppState()
        #expect(appState.canRunSettingsStartupReads == false)
    }

    // MARK: - Sync Activity Tracking

    @Test("Sync activity shows syncing pill while active")
    func syncActivityShowsSyncing() {
        let appState = AppState()

        appState.connectionUI.simulateSyncStarted()
        #expect(appState.statusPillState == .syncing)

        appState.connectionUI.simulateSyncEnded()
        #expect(appState.statusPillState != .syncing)
    }

    // MARK: - UI State Defaults

    @Test("Connection alert state defaults")
    func connectionAlertDefaults() {
        let appState = AppState()
        #expect(appState.connectionUI.showingConnectionFailedAlert == false)
        #expect(appState.connectionUI.connectionFailedMessage == nil)
        #expect(appState.connectionUI.failedPairingDeviceID == nil)
        #expect(appState.connectionUI.pairingFailureKind == nil)
        #expect(appState.connectionUI.otherAppWarningDeviceID == nil)
        #expect(appState.connectionUI.isBusy == false)
        #expect(appState.connectionUI.isNodeStorageFull == false)
    }

    // MARK: - presentPairingFailure / presentConnectionFailure

    @Test("presentPairingFailure(auth) sets pairingFailureKind to .authentication")
    func presentPairingFailureSetsAuthKind() {
        let sut = ConnectionUIState()
        let deviceID = UUID()
        sut.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))

        #expect(sut.failedPairingDeviceID == deviceID)
        #expect(sut.pairingFailureKind == .authentication)
        #expect(sut.connectionFailedTitle != nil)
        #expect(sut.showingConnectionFailedAlert == true)
    }

    @Test("presentPairingFailure(transient) sets pairingFailureKind to .transient")
    func presentPairingFailureSetsTransientKind() {
        let sut = ConnectionUIState()
        let deviceID = UUID()
        sut.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.connectionFailed("timeout")))

        #expect(sut.failedPairingDeviceID == deviceID)
        #expect(sut.pairingFailureKind == .transient)
        #expect(sut.connectionFailedTitle == nil)
        #expect(sut.showingConnectionFailedAlert == true)
    }

    @Test("presentConnectionFailure clears pairingFailureKind set by a prior pairing failure")
    func presentConnectionFailureClearsPairingKind() {
        let sut = ConnectionUIState()
        sut.presentPairingFailure(.connectionFailed(deviceID: UUID(), underlying: BLEError.authenticationFailed))
        #expect(sut.pairingFailureKind == .authentication)

        sut.presentConnectionFailure(message: "generic")

        #expect(sut.pairingFailureKind == nil)
        #expect(sut.connectionFailedTitle == nil)
    }

    // MARK: - handleDisconnect

    @Test("handleDisconnect resets syncActivityCount to zero")
    func handleDisconnectResetsSyncActivityCount() {
        let sut = ConnectionUIState()
        sut.simulateSyncStarted()
        sut.simulateSyncStarted()
        #expect(sut.syncActivityCount == 2)

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        #expect(sut.syncActivityCount == 0)
    }

    @Test("handleDisconnect clears currentSyncPhase")
    func handleDisconnectClearsSyncPhase() {
        let sut = ConnectionUIState()
        sut.currentSyncPhase = .contacts

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        #expect(sut.currentSyncPhase == nil)
    }

    @Test("handleDisconnect sets isNodeStorageFull to false")
    func handleDisconnectClearsNodeStorageFull() {
        let sut = ConnectionUIState()
        sut.isNodeStorageFull = true

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        #expect(sut.isNodeStorageFull == false)
    }

    @Test("handleDisconnect hides ready toast")
    func handleDisconnectHidesReadyToast() {
        let sut = ConnectionUIState()
        sut.showReadyToastBriefly()
        #expect(sut.showReadyToast == true)

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        #expect(sut.showReadyToast == false)
    }

    @Test("handleDisconnect shows disconnected pill when device was paired")
    func handleDisconnectShowsDisconnectedPill() async throws {
        let sut = ConnectionUIState()

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: UUID(),
            shouldSuppressDisconnectedPill: false
        )

        // Disconnected pill shows after 1s delay
        try await Task.sleep(for: .seconds(1.3))
        #expect(sut.disconnectedPillVisible == true)
    }

    @Test("handleDisconnect does not show disconnected pill when suppressed")
    func handleDisconnectSuppressesDisconnectedPill() async throws {
        let sut = ConnectionUIState()

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: UUID(),
            shouldSuppressDisconnectedPill: true
        )

        try await Task.sleep(for: .seconds(1.3))
        #expect(sut.disconnectedPillVisible == false)
    }

    @Test("handleDisconnect does not show disconnected pill without paired device")
    func handleDisconnectNoPairedDevice() async throws {
        let sut = ConnectionUIState()

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        try await Task.sleep(for: .seconds(1.3))
        #expect(sut.disconnectedPillVisible == false)
    }

    @Test("handleDisconnect resets all state in a single call")
    func handleDisconnectResetsAllState() {
        let sut = ConnectionUIState()

        // Set up various dirty state
        sut.simulateSyncStarted()
        sut.simulateSyncStarted()
        sut.currentSyncPhase = .channels
        sut.isNodeStorageFull = true
        sut.showReadyToastBriefly()

        sut.handleDisconnect(
            connectionState: .disconnected,
            lastConnectedDeviceID: nil,
            shouldSuppressDisconnectedPill: false
        )

        #expect(sut.syncActivityCount == 0)
        #expect(sut.currentSyncPhase == nil)
        #expect(sut.isNodeStorageFull == false)
        #expect(sut.showReadyToast == false)
    }
}
