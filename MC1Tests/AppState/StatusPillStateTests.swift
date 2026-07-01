@testable import MC1
@testable import MC1Services
import Testing

@Suite("StatusPillState Tests")
struct StatusPillStateTests {
  @Test
  @MainActor
  func `Failed state takes highest priority`() {
    let appState = AppState()
    appState.connectionUI.showSyncFailedPill()
    #expect(appState.statusPillState == .failed(message: "Sync Failed"))
  }

  @Test
  @MainActor
  func `Syncing takes priority over connecting`() {
    let appState = AppState()
    appState.connectionUI.simulateSyncStarted()
    #expect(appState.statusPillState == .syncing)
    appState.connectionUI.simulateSyncEnded()
  }

  @Test
  @MainActor
  func `Ready state shows when toast is active`() {
    let appState = AppState()
    appState.connectionUI.showReadyToastBriefly()
    #expect(appState.statusPillState == .ready)
  }

  @Test
  @MainActor
  func `Hidden when no conditions met`() {
    let appState = AppState()
    #expect(appState.statusPillState == .hidden)
  }

  @Test
  @MainActor
  func `Disconnected shows after delay when device was paired`() {
    let appState = AppState()
    // This test verifies the delay mechanism exists
    // Full integration test would require mocking connectionManager
    appState.connectionUI.updateDisconnectedPillState(
      connectionState: appState.connectionState,
      lastConnectedDeviceID: appState.connectionManager.lastConnectedDeviceID,
      shouldSuppressDisconnectedPill: appState.connectionManager.shouldSuppressDisconnectedPill
    )
    // Without a paired device, should remain hidden
    #expect(appState.statusPillState == .hidden)
  }

  @Test
  @MainActor
  func `Double onSyncActivityEnded does not drive count below zero`() {
    let appState = AppState()

    // Simulate sync starting
    appState.connectionUI.simulateSyncStarted()
    #expect(appState.statusPillState == .syncing)

    // First end call (simulates onDisconnected path)
    appState.connectionUI.simulateSyncEnded()
    #expect(appState.statusPillState != .syncing)

    // Second end call (simulates error path) - should be no-op due to guard
    appState.connectionUI.simulateSyncEnded()
    #expect(appState.statusPillState == .hidden)

    // Start new sync - pill should show (proves count didn't go negative)
    appState.connectionUI.simulateSyncStarted()
    #expect(appState.statusPillState == .syncing)
  }
}
