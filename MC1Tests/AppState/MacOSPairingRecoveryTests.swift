#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Without a pairing registry, Remove and Try Again must present the scanner
/// immediately; with a registry it still waits for `.active`.
@Suite("macOS pairing recovery")
@MainActor
struct MacOSPairingRecoveryTests {
  @Test
  func `remove and retry on macOS starts the scanner without waiting for foreground`() async throws {
    let pairing = BluetoothScanPairingService()
    let harness = try makeHarness(pairing: pairing)
    defer { harness.cleanup() }
    harness.appState.connectionUI.failedPairingDeviceID = UUID()

    harness.appState.removeFailedPairingAndRetry()
    try await waitUntil(timeout: .seconds(2), "macOS scanner should present") {
      pairing.isPresenting
    }

    #expect(harness.appState.connectionUI.shouldShowPickerOnForeground == false)
    #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
    pairing.cancel()
    try await waitUntil(timeout: .seconds(2), "pairing task should finish after cancel") {
      !harness.appState.connectionUI.isBusy
    }
  }

  @Test
  func `remove and retry on iOS still waits for foreground`() async throws {
    let mockASK = MockAccessorySetupKitService()
    let harness = try makeHarness(pairing: AccessorySetupPairingService(accessorySetupKit: mockASK))
    defer { harness.cleanup() }
    harness.appState.connectionUI.failedPairingDeviceID = UUID()

    harness.appState.removeFailedPairingAndRetry()
    try await waitUntil(timeout: .seconds(2), "iOS remove should finish") {
      harness.appState.connectionUI.failedPairingDeviceID == nil
        && !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.shouldShowPickerOnForeground == true)
    #expect(mockASK.showPickerCallCount == 0)
  }
}

@MainActor
private struct MacOSPairingRecoveryHarness {
  let appState: AppState
  let cleanup: () -> Void
}

@MainActor
private func makeHarness(pairing: any DevicePairingService) throws -> MacOSPairingRecoveryHarness {
  let container = try PersistenceStore.createContainer(inMemory: true)
  let suite = "test.\(UUID().uuidString)"
  let defaults = UserDefaults(suiteName: suite)!
  let manager = ConnectionManager(
    modelContainer: container,
    defaults: defaults,
    pairing: pairing
  )
  let appState = AppState(
    modelContainer: container,
    isPlaceholder: true,
    defaults: defaults,
    injectedConnectionManager: manager
  )
  return MacOSPairingRecoveryHarness(
    appState: appState,
    cleanup: { UserDefaults().removePersistentDomain(forName: suite) }
  )
}
