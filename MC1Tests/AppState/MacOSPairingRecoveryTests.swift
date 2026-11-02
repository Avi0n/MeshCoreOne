#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Remove and Try Again continues into the platform picker after confirmed removal.
@Suite("macOS pairing recovery")
@MainActor
struct MacOSPairingRecoveryTests {
  @Test
  func `remove and retry on macOS starts the scanner without waiting for foreground`() async throws {
    let pairing = BluetoothScanPairingService()
    let harness = try await makeHarness(pairing: pairing, deviceID: UUID())
    do {
      let removal = try #require(harness.appState.removeFailedPairingAndRetry())
      try await waitUntil(timeout: .seconds(2), "macOS scanner should present") {
        pairing.isPresenting
      }

      #expect(harness.appState.isRecoveringPairing)
      #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
      pairing.cancel()
      await removal.value
      try await waitUntil(timeout: .seconds(2), "pairing task should finish after cancel") {
        !harness.appState.connectionUI.isBusy
      }
    } catch {
      pairing.cancel()
      await harness.cleanup()
      throw error
    }
    await harness.cleanup()
  }

  @Test
  func `remove and retry on iOS opens the picker without another foreground event`() async throws {
    let mockASK = MockAccessorySetupKitService()
    let deviceID = UUID()
    mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
    let harness = try await makeHarness(
      pairing: AccessorySetupPairingService(accessorySetupKit: mockASK), deviceID: deviceID
    )

    do {
      let removal = try #require(harness.appState.removeFailedPairingAndRetry())
      await removal.value
      #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
      #expect(!harness.appState.connectionUI.isBusy)
      #expect(!harness.appState.isRecoveringPairing)
      #expect(mockASK.showPickerCallCount == 1)
    } catch {
      await harness.cleanup()
      throw error
    }
    await harness.cleanup()
  }
}

@MainActor
private struct MacOSPairingRecoveryHarness {
  let appState: AppState
  let suite: String

  func cleanup() async {
    await appState.disconnect(reason: .userInitiated)
    appState.shutdown()
    UserDefaults().removePersistentDomain(forName: suite)
  }
}

@MainActor
private func makeHarness(pairing: any DevicePairingService, deviceID: UUID) async throws -> MacOSPairingRecoveryHarness {
  let container = try PersistenceStore.createContainer(inMemory: true)
  let suite = "test.\(UUID().uuidString)"
  let defaults = UserDefaults(suiteName: suite)!
  let transport = MockMeshTransport()
  await transport.setConnectError(BLEError.authenticationFailed)
  let manager = ConnectionManager(
    modelContainer: container,
    defaults: defaults,
    stateMachine: MockBLEStateMachine(),
    transport: transport,
    pairing: pairing
  )
  let appState = AppState(
    modelContainer: container,
    isPlaceholder: true,
    defaults: defaults,
    injectedConnectionManager: manager
  )
  let harness = MacOSPairingRecoveryHarness(appState: appState, suite: suite)
  do {
    do {
      _ = try await appState.connectViaBluetooth(peripheralID: deviceID)
      Issue.record("Physical authentication failure must establish the failed request")
    } catch let failure as ConnectionRequestFailure {
      #expect(manager.isCurrent(failure))
      guard case BLEError.authenticationFailed = failure.underlying else { throw failure }
      appState.connectionUI.presentPairingFailure(
        .connectionFailed(deviceID: deviceID, underlying: failure.underlying)
      )
    }
    #expect(await transport.connectInvocations.count == 1)
    return harness
  } catch {
    await harness.cleanup()
    throw error
  }
}
