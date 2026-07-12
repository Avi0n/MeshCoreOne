import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Connection UI State Tests")
@MainActor
struct ConnectionUIStateTests {
  // MARK: - statusPillState Priority

  @Test
  func `statusPillState is hidden by default`() {
    let appState = AppState()
    #expect(appState.statusPillState == .hidden)
  }

  @Test
  func `Failed state takes priority over syncing`() {
    let appState = AppState()
    appState.connectionUI.simulateSyncStarted()
    appState.connectionUI.showSyncFailedPill()

    #expect(appState.statusPillState == .failed(message: "Sync Failed"))
  }

  @Test
  func `Syncing takes priority over ready toast`() {
    let appState = AppState()
    appState.connectionUI.showReadyToastBriefly()
    appState.connectionUI.simulateSyncStarted()

    #expect(appState.statusPillState == .syncing)
  }

  @Test
  func `Ready toast takes priority over disconnected`() {
    let appState = AppState()
    appState.connectionUI.showReadyToastBriefly()

    // Even if disconnectedPillVisible were true, ready should win
    #expect(appState.statusPillState == .ready)
  }

  @Test
  func `Multiple sync activities keep syncing state until all end`() {
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

  @Test
  func `showReadyToastBriefly sets showReadyToast to true`() {
    let appState = AppState()

    appState.connectionUI.showReadyToastBriefly()

    #expect(appState.connectionUI.showReadyToast == true)
    #expect(appState.statusPillState == .ready)
  }

  @Test
  func `hideReadyToast immediately clears toast`() {
    let appState = AppState()
    appState.connectionUI.showReadyToastBriefly()
    #expect(appState.connectionUI.showReadyToast == true)

    appState.connectionUI.hideReadyToast()

    #expect(appState.connectionUI.showReadyToast == false)
    #expect(appState.statusPillState == .hidden)
  }

  @Test
  func `showReadyToastBriefly auto-hides after delay`() async throws {
    let appState = AppState()

    appState.connectionUI.showReadyToastBriefly()
    #expect(appState.connectionUI.showReadyToast == true)

    // Wait for the 2-second auto-hide plus margin
    try await Task.sleep(for: .seconds(2.3))

    #expect(appState.connectionUI.showReadyToast == false)
  }

  @Test
  func `Calling showReadyToastBriefly again resets the timer`() async throws {
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

  @Test
  func `showSyncFailedPill sets visible flag`() {
    let appState = AppState()

    appState.connectionUI.showSyncFailedPill()

    #expect(appState.connectionUI.syncFailedPillVisible == true)
    #expect(appState.statusPillState == .failed(message: "Sync Failed"))
  }

  @Test
  func `hideSyncFailedPill immediately clears pill`() {
    let appState = AppState()
    appState.connectionUI.showSyncFailedPill()

    appState.connectionUI.hideSyncFailedPill()

    #expect(appState.connectionUI.syncFailedPillVisible == false)
  }

  @Test
  func `showSyncFailedPill auto-hides after delay`() async throws {
    let appState = AppState()

    appState.connectionUI.showSyncFailedPill()
    #expect(appState.connectionUI.syncFailedPillVisible == true)

    // Wait for the 7-second auto-hide plus margin
    try await Task.sleep(for: .seconds(7.3))

    #expect(appState.connectionUI.syncFailedPillVisible == false)
  }

  // MARK: - Disconnected Pill

  @Test
  func `disconnectedPillVisible is false by default`() {
    let appState = AppState()
    #expect(appState.connectionUI.disconnectedPillVisible == false)
  }

  @Test
  func `hideDisconnectedPill clears pill immediately`() {
    let appState = AppState()

    appState.connectionUI.hideDisconnectedPill()

    #expect(appState.connectionUI.disconnectedPillVisible == false)
  }

  @Test
  func `updateDisconnectedPillState without paired device stays hidden`() async throws {
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

  @Test
  func `canRunSettingsStartupReads is false when disconnected`() {
    let appState = AppState()
    #expect(appState.canRunSettingsStartupReads == false)
  }

  // MARK: - Sync Activity Tracking

  @Test
  func `Sync activity shows syncing pill while active`() {
    let appState = AppState()

    appState.connectionUI.simulateSyncStarted()
    #expect(appState.statusPillState == .syncing)

    appState.connectionUI.simulateSyncEnded()
    #expect(appState.statusPillState != .syncing)
  }

  // MARK: - UI State Defaults

  @Test
  func `Connection alert state defaults`() {
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

  @Test
  func `presentPairingFailure(auth) sets pairingFailureKind to .authentication`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()
    sut.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.authenticationFailed))

    #expect(sut.failedPairingDeviceID == deviceID)
    #expect(sut.pairingFailureKind == .authentication)
    #expect(sut.connectionFailedTitle != nil)
    #expect(sut.showingConnectionFailedAlert == true)
  }

  @Test
  func `presentPairingFailure(transient) sets pairingFailureKind to .transient`() {
    let sut = ConnectionUIState()
    let deviceID = UUID()
    sut.presentPairingFailure(.connectionFailed(deviceID: deviceID, underlying: BLEError.connectionFailed("timeout")))

    #expect(sut.failedPairingDeviceID == deviceID)
    #expect(sut.pairingFailureKind == .transient)
    #expect(sut.connectionFailedTitle == nil)
    #expect(sut.showingConnectionFailedAlert == true)
  }

  @Test
  func `presentConnectionFailure clears pairingFailureKind set by a prior pairing failure`() {
    let sut = ConnectionUIState()
    sut.presentPairingFailure(.connectionFailed(deviceID: UUID(), underlying: BLEError.authenticationFailed))
    #expect(sut.pairingFailureKind == .authentication)

    sut.presentConnectionFailure(message: "generic")

    #expect(sut.pairingFailureKind == nil)
    #expect(sut.connectionFailedTitle == nil)
  }

  // MARK: - handleDisconnect

  @Test
  func `handleDisconnect resets syncActivityCount to zero`() {
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

  @Test
  func `handleDisconnect clears currentSyncPhase`() {
    let sut = ConnectionUIState()
    sut.currentSyncPhase = .contacts

    sut.handleDisconnect(
      connectionState: .disconnected,
      lastConnectedDeviceID: nil,
      shouldSuppressDisconnectedPill: false
    )

    #expect(sut.currentSyncPhase == nil)
  }

  @Test
  func `handleDisconnect sets isNodeStorageFull to false`() {
    let sut = ConnectionUIState()
    sut.isNodeStorageFull = true

    sut.handleDisconnect(
      connectionState: .disconnected,
      lastConnectedDeviceID: nil,
      shouldSuppressDisconnectedPill: false
    )

    #expect(sut.isNodeStorageFull == false)
  }

  @Test
  func `handleDisconnect hides ready toast`() {
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

  @Test
  func `handleDisconnect shows disconnected pill when device was paired`() async throws {
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

  @Test
  func `handleDisconnect does not show disconnected pill when suppressed`() async throws {
    let sut = ConnectionUIState()

    sut.handleDisconnect(
      connectionState: .disconnected,
      lastConnectedDeviceID: UUID(),
      shouldSuppressDisconnectedPill: true
    )

    try await Task.sleep(for: .seconds(1.3))
    #expect(sut.disconnectedPillVisible == false)
  }

  @Test
  func `handleDisconnect does not show disconnected pill without paired device`() async throws {
    let sut = ConnectionUIState()

    sut.handleDisconnect(
      connectionState: .disconnected,
      lastConnectedDeviceID: nil,
      shouldSuppressDisconnectedPill: false
    )

    try await Task.sleep(for: .seconds(1.3))
    #expect(sut.disconnectedPillVisible == false)
  }

  @Test
  func `handleDisconnect resets all state in a single call`() {
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
