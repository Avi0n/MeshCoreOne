#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

@Suite("System pairing setup AppState")
@MainActor
struct SystemPairingSetupAppStateTests {
  @Test
  func `scan with two unsaved ASK accessories sets the plural prompt and does not show the picker`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idB = UUID()
    let idC = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: idB, displayName: "B"),
      ASAccessory(bluetoothIdentifier: idC, displayName: "C")
    ])

    harness.appState.startDeviceScan()
    try await waitUntil(timeout: .seconds(2), "prompt should appear") {
      harness.appState.connectionUI.pendingSystemPairingSetup != nil
        && !harness.appState.connectionUI.isBusy
    }

    let prompt = try #require(harness.appState.connectionUI.pendingSystemPairingSetup)
    #expect(prompt.accessories.map(\.id) == [idB, idC])
    #expect(harness.mockASK.showPickerCallCount == 0)
    #expect(harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `forgetting one of two unsaved accessories opens the picker and leaves the sibling`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idB = UUID()
    let idC = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: idB, displayName: "B"),
      ASAccessory(bluetoothIdentifier: idC, displayName: "C")
    ])
    harness.mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: idB, name: "B")]
    )

    harness.appState.confirmSystemPairingSetup()
    try await waitUntil(timeout: .seconds(2), "confirm should finish") {
      !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.pendingSystemPairingSetup == nil)
    #expect(harness.mockASK.lastRemovedDeviceID == idB)
    #expect(harness.mockASK.showPickerCallCount == 1)
    #expect(harness.mockASK.pairedAccessories.compactMap(\.bluetoothIdentifier) == [idC])
  }

  @Test
  func `declining iOS Remove Accessory does not fail connection or open the picker`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idB = UUID()
    let idC = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: idB, displayName: "B"),
      ASAccessory(bluetoothIdentifier: idC, displayName: "C")
    ])
    #if canImport(UIKit)
      harness.mockASK.removeAccessoryError = NSError(
        domain: ASError.errorDomain,
        code: ASError.Code.userCancelled.rawValue
      )
    #endif
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [
        SystemPairedAccessory(id: idB, name: "B"),
        SystemPairedAccessory(id: idC, name: "C")
      ]
    )

    harness.appState.confirmSystemPairingSetup()
    try await waitUntil(timeout: .seconds(2), "confirm should finish") {
      !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.showingConnectionFailedAlert == false)
    #expect(harness.appState.connectionUI.pairingFailureKind == nil)
    #expect(harness.mockASK.showPickerCallCount == 0)
    let remaining = try await harness.appState.connectionManager.systemAccessoriesMissingDeviceRecord()
    #expect(Set(remaining.map(\.id)) == Set([idB, idC]))
  }

  @Test
  func `pickerRestricted after Forget does not fail connection and retries on become-active`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idB = UUID()
    harness.mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: idB, displayName: "B")])
    harness.mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: idB, name: "B")]
    )

    harness.appState.confirmSystemPairingSetup()
    try await waitUntil(timeout: .seconds(2), "first picker attempt should finish") {
      !harness.appState.connectionUI.isBusy
        && harness.appState.connectionUI.shouldCompleteFreshPairingOnForeground
    }

    #expect(harness.appState.connectionUI.showingConnectionFailedAlert == false)
    #expect(harness.mockASK.showPickerCallCount == 1)
    #expect(harness.appState.connectionManager.shouldDeferOpportunisticReconnect)

    harness.mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
    harness.appState.handleBecameActive()
    try await waitUntil(timeout: .seconds(2), "foreground retry should present the picker") {
      harness.mockASK.showPickerCallCount == 2
        && !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.showingConnectionFailedAlert == false)
    #expect(harness.appState.connectionUI.shouldCompleteFreshPairingOnForeground == false)
    #expect(!harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `auth failure after Forget routes through presentFreshPairingFailure`() async throws {
    let harness = try makeHarness(injectTransport: true)
    defer { harness.cleanup() }
    let idB = UUID()
    harness.mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: idB, displayName: "B")])
    harness.mockASK.setPickerResult(.success(idB))
    harness.mockASK.isSessionActive = false
    await harness.transport?.setConnectError(BLEError.authenticationFailed)
    harness.appState.connectionManager.otherAppWaitStrategyOverride = { _ in false }
    harness.appState.connectionManager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: idB, name: "B")]
    )

    harness.appState.confirmSystemPairingSetup()
    try await waitUntil(timeout: .seconds(5), "auth failure should present") {
      !harness.appState.connectionUI.isBusy
        && harness.appState.connectionUI.showingConnectionFailedAlert
    }

    #expect(harness.appState.connectionUI.pairingFailureKind == .pinRejected)
    #expect(harness.appState.connectionUI.connectionFailedTitle != nil)
    #expect(harness.appState.connectionUI.failedPairingDeviceID == idB)
  }

  @Test
  func `device-selection dismiss promotes a queued setup prompt and does not open the picker`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let id = UUID()
    harness.appState.connectionUI.queuedSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: id, name: "Stray")]
    )

    harness.appState.handleDeviceSelectionSheetDismissed()

    let prompt = try #require(harness.appState.connectionUI.pendingSystemPairingSetup)
    #expect(prompt.accessories.map(\.id) == [id])
    #expect(harness.appState.connectionUI.queuedSystemPairingSetup == nil)
    #expect(harness.mockASK.showPickerCallCount == 0)
  }

  @Test
  func `queued scan while connected presents leftover Forget without dropping the live radio`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let liveID = UUID()
    let leftoverID = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: leftoverID, displayName: "Leftover")
    ])
    harness.appState.connectionManager.setTestState(
      connectionState: .connected,
      connectedDevice: makeDevice(id: liveID),
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    harness.appState.connectionUI.queuedDeviceScanAfterSelectionDismiss = true

    harness.appState.handleDeviceSelectionSheetDismissed()
    try await waitUntil(timeout: .seconds(2), "queued scan should set the prompt") {
      harness.appState.connectionUI.pendingSystemPairingSetup != nil
        && !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.pendingSystemPairingSetup?.accessories.map(\.id) == [leftoverID])
    #expect(harness.appState.connectionManager.connectionState == .connected)
    #expect(harness.appState.connectionManager.connectedDevice?.id == liveID)
    #expect(harness.appState.connectionManager.connectionIntent.wantsConnection)
    #expect(harness.mockASK.showPickerCallCount == 0)

    harness.appState.cancelSystemPairingSetup()

    #expect(harness.appState.connectionUI.pendingSystemPairingSetup == nil)
    #expect(harness.appState.connectionManager.connectionState == .connected)
    #expect(harness.appState.connectionManager.connectedDevice?.id == liveID)
    #expect(harness.appState.connectionManager.connectionIntent.wantsConnection)
    #expect(!harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `setup-sheet swipe dismiss (pending already nil) ends pairing-flow deferral`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    harness.appState.connectionManager.isPairingFlowActive = true
    harness.appState.connectionUI.pendingSystemPairingSetup = nil

    harness.appState.handleSystemPairingSetupSheetDismissed()

    #expect(harness.appState.connectionUI.pendingSystemPairingSetup == nil)
    #expect(!harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `setup-sheet onDismiss with a replacement prompt keeps it`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let id = UUID()
    harness.appState.connectionManager.isPairingFlowActive = true
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: id, name: "B")]
    )

    harness.appState.handleSystemPairingSetupSheetDismissed()

    let prompt = try #require(harness.appState.connectionUI.pendingSystemPairingSetup)
    #expect(prompt.accessories.map(\.id) == [id])
    #expect(harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `device-selection dismiss does not replace a live setup prompt`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let liveID = UUID()
    let queuedID = UUID()
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: liveID, name: "Live")]
    )
    harness.appState.connectionUI.queuedSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: queuedID, name: "Queued")]
    )
    harness.appState.connectionManager.isPairingFlowActive = true

    harness.appState.handleDeviceSelectionSheetDismissed()

    let prompt = try #require(harness.appState.connectionUI.pendingSystemPairingSetup)
    #expect(prompt.accessories.map(\.id) == [liveID])
    #expect(harness.appState.connectionUI.queuedSystemPairingSetup == nil)
    #expect(harness.mockASK.showPickerCallCount == 0)
  }

  @Test
  func `startDeviceScan does not replace a live setup prompt`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idA = UUID()
    let idB = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: idB, displayName: "B")
    ])
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: idA, name: "A")]
    )
    harness.appState.connectionManager.isPairingFlowActive = true

    harness.appState.startDeviceScan()
    try await waitUntil(timeout: .seconds(2), "scan should finish") {
      !harness.appState.connectionUI.isBusy
    }

    let prompt = try #require(harness.appState.connectionUI.pendingSystemPairingSetup)
    #expect(prompt.accessories.map(\.id) == [idA])
    #expect(harness.mockASK.showPickerCallCount == 0)
    #expect(harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `became-active picker retry is skipped while setup is pending`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let id = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: id, displayName: "B")
    ])
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: id, name: "B")]
    )
    harness.appState.connectionUI.shouldShowPickerOnForeground = true
    harness.appState.connectionManager.isPairingFlowActive = true

    harness.appState.handleBecameActive()

    #expect(harness.appState.connectionUI.shouldShowPickerOnForeground)
    #expect(harness.appState.connectionUI.pendingSystemPairingSetup?.accessories.map(\.id) == [id])
    #expect(harness.appState.connectionUI.isBusy == false)
    #expect(harness.mockASK.showPickerCallCount == 0)
  }

  @Test
  func `became-active picker retry still scans when no setup prompt is pending`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let id = UUID()
    harness.mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: id, displayName: "B")
    ])
    harness.appState.connectionUI.shouldShowPickerOnForeground = true

    harness.appState.handleBecameActive()
    try await waitUntil(timeout: .seconds(2), "foreground scan should set the prompt") {
      harness.appState.connectionUI.pendingSystemPairingSetup != nil
        && !harness.appState.connectionUI.isBusy
    }

    #expect(harness.appState.connectionUI.shouldShowPickerOnForeground == false)
    #expect(harness.appState.connectionUI.pendingSystemPairingSetup?.accessories.map(\.id) == [id])
    #expect(harness.mockASK.showPickerCallCount == 0)
  }

  @Test
  func `cancelling setup ends pairing-flow deferral`() throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    harness.appState.connectionManager.isPairingFlowActive = true
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: UUID(), name: "B")]
    )

    harness.appState.cancelSystemPairingSetup()

    #expect(harness.appState.connectionUI.pendingSystemPairingSetup == nil)
    #expect(!harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  @Test
  func `setup-sheet onDismiss during confirm does not end pairing-flow deferral`() async throws {
    let harness = try makeHarness()
    defer { harness.cleanup() }
    let idB = UUID()
    harness.mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: idB, displayName: "B")])
    harness.mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
    harness.appState.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(
      accessories: [SystemPairedAccessory(id: idB, name: "B")]
    )

    harness.appState.confirmSystemPairingSetup()
    harness.appState.handleSystemPairingSetupSheetDismissed()
    #expect(harness.appState.connectionManager.shouldDeferOpportunisticReconnect)

    try await waitUntil(timeout: .seconds(2), "confirm should finish") {
      !harness.appState.connectionUI.isBusy
    }
    #expect(!harness.appState.connectionManager.shouldDeferOpportunisticReconnect)
  }

  private func makeDevice(id: UUID) -> DeviceDTO {
    DeviceDTO(
      id: id,
      radioID: id,
      publicKey: Data(repeating: 0x01, count: 32),
      nodeName: "TestDevice",
      firmwareVersion: 9,
      firmwareVersionString: "v1.13.0",
      manufacturerName: "TestMfg",
      buildDate: "01 Jan 2025",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: false,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: [.bluetooth(peripheralUUID: id, displayName: "Radio")]
    )
  }

  private struct Harness {
    let appState: AppState
    let mockASK: MockAccessorySetupKitService
    let transport: MockMeshTransport?
    let cleanup: () -> Void
  }

  private func makeHarness(injectTransport: Bool = false) throws -> Harness {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suite = "test.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    let mockASK = MockAccessorySetupKitService()
    let transport = injectTransport ? MockMeshTransport() : nil
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      transport: transport,
      pairing: AccessorySetupPairingService(accessorySetupKit: mockASK)
    )
    let appState = AppState(
      modelContainer: container,
      isPlaceholder: true,
      defaults: defaults,
      injectedConnectionManager: manager
    )
    return Harness(
      appState: appState,
      mockASK: mockASK,
      transport: transport,
      cleanup: { UserDefaults().removePersistentDomain(forName: suite) }
    )
  }
}
