import AccessorySetupKit
import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Pairing recovery ownership", .serialized, .timeLimit(.minutes(1)))
@MainActor
struct PairingRecoveryOwnershipTests {
  @Test(arguments: [false, true])
  func `confirmed removal opens the picker without another foreground event`(hasWiFi: Bool) async throws {
    try await withHarness(hasWiFi: hasWiFi) { env in
      let operation = try #require(env.app.removeFailedPairingAndRetry())
      await operation.value

      #expect(env.picker.showPickerCallCount == 1)
      #expect(!env.app.connectionUI.isBusy)
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
      let saved = try #require(await env.manager.persistenceStore.fetchDevice(publicKey: env.device.publicKey))
      #expect(saved.radioID == env.device.radioID)
      #expect(saved.connectionMethods == env.device.connectionMethods.filter(\.isWiFi))
    }
  }

  @Test
  func `a system removal error preserves the failure episode and shows the error`() async throws {
    try await withHarness { env in
      let episode = env.app.connectionUI.pairingFailureEpisodeID
      let previous = try #require(env.manager.currentConnection)
      env.picker.removeAccessoryError = NSError(domain: "RemovalFailure", code: 1)
      let operation = try #require(env.app.removeFailedPairingAndRetry())
      await operation.value

      #expect(env.app.connectionUI.showingConnectionFailedAlert)
      #expect(env.app.connectionUI.failedPairingDeviceID == env.device.id)
      #expect(env.app.connectionUI.pairingFailureEpisodeID == episode)
      #expect(env.picker.showPickerCallCount == 0)
      #expect(env.manager.isCurrent(previous))
      #expect(!env.app.isRecoveringPairing)
      #expect(!env.manager.isPairingFlowActive)
    }
  }

  @Test
  func `re-pairing presentation outlives busy work and clears for a live successor`() async throws {
    try await withHarness { env in
      env.picker.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))
      let operation = try #require(env.app.removeFailedPairingAndRetry())
      await operation.value
      #expect(env.app.connectionUI.shouldCompleteFreshPairingOnForeground)
      #expect(!env.app.connectionUI.isBusy)
      #expect(env.app.isRecoveringPairing)
      #expect(env.manager.isPairingFlowActive)
      env.app.startDeviceScan()
      #expect(env.app.isRecoveringPairing)
      #expect(env.picker.showPickerCallCount == 1)

      let result = try await env.manager.connectViaWiFi(host: "successor.local", port: 4242)
      #expect(env.manager.isCurrent(result))
      env.app.handleBecameActive()
      #expect(!env.app.isRecoveringPairing)
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.isBusy)
      #expect(!env.app.connectionUI.shouldCompleteFreshPairingOnForeground)
      #expect(env.picker.showPickerCallCount == 1)
      #expect(env.manager.isCurrent(result))
    }
  }

  @Test
  func `scan during removal keeps the failure episode and admits one picker`() async throws {
    try await withHarness { env in
      let gate = AsyncStream<Void>.makeStream()
      defer { gate.continuation.finish() }
      env.picker.beforeRemovalResult = {
        for await _ in gate.stream {
          break
        }
      }
      let episode = env.app.connectionUI.pairingFailureEpisodeID
      let operation = try #require(env.app.removeFailedPairingAndRetry())
      try await waitUntil("system removal must own its confirmation") { env.picker.removeAccessoryCallCount == 1 }

      env.app.startDeviceScan()
      #expect(env.app.connectionUI.failedPairingDeviceID == env.device.id)
      #expect(env.app.connectionUI.pairingFailureEpisodeID == episode)
      #expect(env.picker.showPickerCallCount == 0)
      gate.continuation.finish()
      await operation.value
      #expect(env.picker.showPickerCallCount == 1)
      #expect(!env.app.connectionUI.isBusy)
      #expect(!env.manager.isPairingFlowActive)
    }
  }

  @Test
  func `a real successor discards a pending foreground picker retry`() async throws {
    try await withHarness(connected: false) { env in
      env.picker.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))
      env.app.startDeviceScan()
      try await waitUntil("picker rejection must arm its scoped retry") {
        env.app.connectionUI.shouldCompleteFreshPairingOnForeground && !env.app.connectionUI.isBusy
      }
      let result = try await env.manager.connectViaWiFi(host: "successor.local", port: 4242)
      guard case let .operational(successor) = result else {
        Issue.record("the replacement must install a real operational connection")
        return
      }
      env.app.handleBecameActive()
      try await waitUntil("the obsolete foreground flow must settle") {
        !env.app.connectionUI.shouldCompleteFreshPairingOnForeground && !env.app.connectionUI.isBusy
      }

      #expect(env.picker.showPickerCallCount == 1)
      #expect(env.manager.isCurrent(successor))
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
      #expect(try await env.manager.session?.getTime() != nil)
    }
  }

  @Test
  func `a replacement scan joins the previous picker before presenting another`() async throws {
    try await withHarness(connected: false) { env in
      let gate = AsyncStream<Void>.makeStream()
      defer { gate.continuation.finish() }
      env.picker.beforePickerResultAsync = {
        if env.picker.showPickerCallCount == 1 {
          for await _ in gate.stream {
            break
          }
        }
      }
      env.app.startDeviceScan()
      try await waitUntil("the first scan must enter the actual picker") { env.picker.showPickerCallCount == 1 }
      env.app.startDeviceScan()
      gate.continuation.finish()
      try await waitUntil("the new scan must present after the first unwinds") {
        env.picker.showPickerCallCount == 2 && !env.app.connectionUI.isBusy
      }
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
    }
  }

  @Test
  func `cancelling an explicit switch keeps the previous operational connection`() async throws {
    try await withHarness { env in
      let previous = try #require(env.manager.currentConnection)
      env.app.startDeviceScan()
      try await waitUntil("the cancelled picker must settle") {
        env.picker.showPickerCallCount == 1 && !env.app.connectionUI.isBusy
      }
      #expect(env.manager.isCurrent(previous))
      #expect(env.manager.connectedDevice?.radioID == env.device.radioID)
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
      #expect(try await env.manager.session?.getTime() != nil)
    }
  }

  @Test
  func `an obsolete delayed retry releases its claim without clearing newer busy state`() async throws {
    try await withHarness(connected: false) { env in
      env.picker.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))
      env.app.startDeviceScan()
      try await waitUntil("the first picker must leave a pending retry") {
        env.app.connectionUI.shouldCompleteFreshPairingOnForeground && !env.app.connectionUI.isBusy
      }
      let next = env.app.connectionUI.beginConnectionAction()
      defer { env.app.connectionUI.finishConnectionAction(next) }
      try await waitUntil("the delayed retry must release the obsolete pairing claim") {
        !env.manager.isPairingFlowActive
      }
      #expect(env.app.connectionUI.isCurrentConnectionAction(next))
      #expect(env.app.connectionUI.isBusy)
      #expect(!env.app.connectionUI.shouldCompleteFreshPairingOnForeground)
      #expect(env.picker.showPickerCallCount == 1)
    }
  }

  @Test(arguments: PresentationSurface.allCases)
  func `recovery replaces the scan control on each real presentation`(surface: PresentationSurface) async throws {
    try await withHarness(hasWiFi: surface == .savedSelection) { env in
      let gate = AsyncStream<Void>.makeStream()
      defer { gate.continuation.finish() }
      env.picker.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))
      env.picker.beforePickerResultAsync = {
        if env.picker.showPickerCallCount > 1 {
          for await _ in gate.stream {
            break
          }
        }
      }
      let operation = try #require(env.app.removeFailedPairingAndRetry())
      await operation.value
      #expect(env.app.isRecoveringPairing)
      let host = try SettingsTabHarness.make(connected: false, injectedAppState: env.app)
      defer { host.tearDown() }
      let content: AnyView = surface == .onboarding ? AnyView(DeviceScanView()) : AnyView(DeviceSelectionSheet())
      host.window.rootViewController = UIHostingController(rootView: content.environment(\.appState, env.app))
      host.window.makeKeyAndVisible()
      host.window.layoutIfNeeded()
      let status = L10n.Settings.DeviceSelection.rePairing
      let showedRecovery = try await host.waitUntil {
        let labels = host.labels(in: host.window)
        guard labels.contains(status) else { return false }
        switch surface {
        case .onboarding: return true
        case .emptySelection: return labels.contains { $0.contains(L10n.Settings.DeviceSelection.noPairedDevices) }
        case .savedSelection: return labels.contains { $0.contains(env.device.nodeName) }
        }
      }
      try #require(showedRecovery, "The rendered recovery control must carry its localized label")
      let labels = host.labels(in: host.window)
      #expect(!labels.contains(L10n.Onboarding.DeviceScan.addDevice))
      #expect(!labels.contains(L10n.Settings.DeviceSelection.scanForDevices))
      #expect(!labels.contains(L10n.Settings.DeviceSelection.scanBluetooth))

      env.app.cancelFreshPairingOperation()
      gate.continuation.finish()
      let clearedRecovery = try await host.waitUntil { !host.labels(in: host.window).contains(status) }
      #expect(clearedRecovery)
      #expect(!env.manager.isPairingFlowActive)
      #expect(!env.app.connectionUI.isBusy)
    }
  }

  enum PresentationSurface: CaseIterable, Sendable {
    case onboarding
    case emptySelection
    case savedSelection
  }

  private func withHarness(
    connected: Bool = true,
    hasWiFi: Bool = false,
    _ body: @MainActor (Harness) async throws -> Void
  ) async throws {
    let env = try await Harness(connected: connected, hasWiFi: hasWiFi)
    do {
      try await body(env)
    } catch {
      await env.close()
      throw error
    }
    await env.close()
  }

  @MainActor
  private final class Harness {
    let app: AppState
    let manager: ConnectionManager
    let picker = MockAccessorySetupKitService()
    let ble = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
    let wifi = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB2, count: ProtocolLimits.publicKeySize))
    let device: DeviceDTO
    private let suite = "test.pairing-recovery.\(UUID())"

    init(connected: Bool, hasWiFi: Bool) async throws {
      let defaults = try #require(UserDefaults(suiteName: suite))
      defaults.set(false, forKey: AppStorageKey.hasCompletedOnboarding.rawValue)
      let container = try PersistenceStore.createContainer(inMemory: true)
      let deviceID = UUID()
      var methods: [ConnectionMethod] = [.bluetooth(peripheralUUID: deviceID, displayName: "Radio")]
      if hasWiFi { methods.append(.wifi(host: "radio.local", port: 4242, displayName: nil)) }
      device = DeviceDTO(from: Device(
        id: deviceID, radioID: UUID(), publicKey: ble.publicKey, nodeName: "Radio", connectionMethods: methods
      ))
      if connected {
        picker.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
      }
      picker.isSessionActive = false
      picker.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
      let wifi = wifi
      manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: ble,
        pairing: AccessorySetupPairingService(accessorySetupKit: picker), makeWiFiTransport: { wifi }
      )
      manager.otherAppWaitStrategyOverride = { _ in false }
      app = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
      if connected {
        try await manager.persistenceStore.saveDevice(device)
        let result = try await manager.connect(to: deviceID)
        #expect(manager.isCurrent(result))
        app.connectionUI.failedPairingDeviceID = deviceID
      }
    }

    func close() async {
      picker.beforePickerResultAsync = nil
      picker.beforeRemovalResult = nil
      await ble.releaseAllGates()
      await wifi.releaseAllGates()
      _ = await app.disconnect(reason: .userInitiated)
      app.tearDownAppStateSessionState()
      app.shutdown()
      await manager.retirementTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
    }
  }
}
