import AccessorySetupKit
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Connection recovery caller boundaries")
@MainActor
struct ConnectionRecoveryCallerBoundaryTests {
  @Test(arguments: [false, true])
  func `owned BLE rebuild settles actual pairing and settings switch`(pairing: Bool) async throws {
    let env = try Harness(paired: !pairing)
    if pairing { #expect(!env.app.onboarding.hasCompletedOnboarding) }
    var switchWorker: Task<Void, Never>?
    do {
      if !pairing {
        _ = try await env.manager.connect(to: env.deviceID)
        _ = try await env.manager.connectViaWiFi(host: "radio.local", port: 4242)
      }
      let previousTimeReads = await env.ble.timeReads
      await env.ble.holdReplies(for: .getTime)
      if pairing {
        env.app.startDeviceScan()
      } else {
        switchWorker = Task { await env.app.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "radio.local") }
      }
      try await waitUntil("the app caller must publish BLE and reach its real clock tail") {
        await env.ble.timeReads == previousTimeReads + 1
      }
      let first = try #require(env.manager.currentConnection)
      if !pairing { #expect(env.app.connectionLinkSwitch?.target == .bluetooth) }
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("post-publication physical loss must retire the Link") { env.manager.currentConnection == nil }
      await env.ble.releaseReplies(for: .getTime)
      try await waitUntil("the retired setup must release its barrier") { env.manager.connectionTeardownCount == 0 }
      await env.ble.simulateReconnected()
      try await waitUntil("OS recovery must rebuild the same request") {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != first.generation
      }
      await switchWorker?.value
      #expect(env.manager.connectionRequestID == first.requestID)
      if pairing {
        try await waitUntil(timeout: .seconds(3), "fresh pairing must automatically advance after owned recovery") {
          env.app.onboarding.onboardingPath.contains(.region)
        }
        #expect(!env.app.connectionUI.isBusy)
      } else {
        #expect(env.app.connectionLinkSwitch?.target == nil, "same-radio Settings switch must settle after owned recovery")
      }
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch {
      await env.ble.releaseAllGates()
      switchWorker?.cancel()
      await switchWorker?.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `genuinely superseded pairing does not advance onboarding`() async throws {
    let env = try Harness()
    var next: Task<ConnectionResult, Error>?
    do {
      await env.ble.holdReplies(for: .getTime)
      env.app.startDeviceScan()
      try await waitUntil("pairing must reach the published clock tail") { await env.ble.timeReads == 1 }
      let first = try #require(env.manager.currentConnection)
      next = Task { try await env.manager.connectViaWiFi(host: "replacement.local", port: 4242) }
      try await waitUntil("replacement must supersede the pairing request") { env.manager.connectionRequestID != first.requestID }
      await env.ble.releaseAllGates()
      #expect(try await env.manager.isCurrent(#require(next).value))
      try await waitUntil("superseded pairing caller must settle") { !env.app.connectionUI.isBusy }
      #expect(!env.app.onboarding.onboardingPath.contains(.region))
      #expect(!env.app.connectionUI.showingConnectionFailedAlert)
    } catch {
      await env.ble.releaseAllGates()
      next?.cancel()
      _ = await next?.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `stale recovery controls preserve a same or different radio successor`(sameRadio: Bool) async throws {
    try await withHarness(foreignWiFiRadio: !sameRadio) { env in
      _ = try await env.manager.connect(to: env.deviceID)
      let first = try #require(env.manager.currentConnection)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("recovery must retire the old Link") { env.manager.currentConnection == nil }
      let captured = try #require(env.manager.connectionActivity)
      #expect(captured.requestID == first.requestID)
      #expect(captured.phase == .reconnecting)
      try await waitUntil("old cleanup must finish before physical recovery") { env.manager.connectionTeardownCount == 0 }
      await env.ble.simulateReconnected()
      try await waitUntil("the original request must recover before choosing another link") {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != first.generation
      }

      await env.wifi.holdConnect()
      env.startWiFiSwitch()
      try await waitUntil("the successor switch must reach its physical connect") { await env.wifi.connectIsHeld }
      let successorID = try #require(env.manager.connectionRequestID)
      let record = try #require(env.app.connectionLinkSwitch)
      let pendingPill = env.app.statusPillState
      let disconnects = await env.wifi.disconnectCount
      #expect(successorID != captured.requestID)
      #expect(record.target == .wifi)

      await env.app.disconnect(requestID: captured.requestID)

      #expect(env.manager.connectionRequestID == successorID)
      #expect(env.app.connectionLinkSwitch == record)
      #expect(env.app.statusPillState == pendingPill)
      #expect(env.app.connectionState == .connecting)
      #expect(env.manager.connectionActivity?.requestID == successorID)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(await env.wifi.disconnectCount == disconnects)
      await env.wifi.releaseConnect()
      await env.finishWorkers()
      let successor = try #require(env.manager.currentConnection)
      let session = try #require(env.manager.session)
      #expect(successor.requestID == successorID)
      #expect((successor.device.radioID == first.device.radioID) == sameRadio)
      #expect(env.app.connectionState == .ready)

      await env.app.disconnect(requestID: captured.requestID)

      #expect(env.manager.currentConnection?.generation == successor.generation)
      #expect(env.manager.connectionRequestID == successorID)
      #expect(env.app.services === successor.services)
      #expect(env.manager.session === session)
      #expect(env.app.connectedDevice?.radioID == successor.device.radioID)
      #expect(env.app.connectionState == .ready)
      #expect(env.manager.connectionActivity?.phase == .ready)
      #expect(!env.defaults.bool(forKey: PersistenceKeys.userExplicitlyDisconnected))
      #expect(await env.wifi.disconnectCount == disconnects)
      let expectedTime = Date(timeIntervalSince1970: 1_800_000_000)
      await env.wifi.setRadioTime(expectedTime)
      #expect(try await session.getTime() == expectedTime)
    }
  }

  @Test
  func `a captured recovery control stops its request after Ready and persists the choice`() async throws {
    try await withHarness { env in
      _ = try await env.manager.connect(to: env.deviceID)
      let first = try #require(env.manager.currentConnection)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("recovery must retire the old Link") { env.manager.currentConnection == nil }
      let captured = try #require(env.manager.connectionActivity)
      #expect(captured.requestID == first.requestID)
      #expect(captured.phase == .reconnecting)
      #expect(captured.statusTitle == L10n.Settings.BleStatus.Status.reconnecting)
      #expect(captured.stopTitle == L10n.Settings.BleStatus.stopReconnecting)
      let name = try #require(captured.targetName)
      #expect(captured.accessibilityValue == L10n.Settings.BleStatus.accessibilityValue(name, captured.statusTitle))
      #expect(env.app.statusPillState == .reconnecting)
      try await waitUntil("old cleanup must finish before physical recovery") { env.manager.connectionTeardownCount == 0 }
      await env.ble.simulateReconnected()
      try await waitUntil("the captured request must recover before the user taps Stop") {
        env.manager.connectionState == .ready && env.manager.currentConnection?.generation != first.generation
      }
      #expect(env.manager.connectionActivity?.requestID == captured.requestID)
      #expect(env.manager.connectionActivity?.stopTitle == L10n.Settings.BleStatus.disconnect)

      await env.app.disconnect(requestID: captured.requestID)

      #expect(env.app.connectionState == .disconnected)
      #expect(env.app.connectedDevice == nil)
      #expect(env.app.services == nil)
      #expect(env.manager.session == nil)
      #expect(env.manager.connectionActivity == nil)
      #expect(env.manager.connectionIntent == .userDisconnected)
      #expect(env.defaults.bool(forKey: PersistenceKeys.userExplicitlyDisconnected))
      #expect(env.app.statusPillState == .hidden)
      #expect(await env.ble.isConnected == false)
    }
  }

  @Test
  func `dismissing device selection leaves active recovery and its Stop control intact`() async throws {
    try await withHarness { env in
      _ = try await env.manager.connect(to: env.deviceID)
      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("recovery must retire the old Link") { env.manager.currentConnection == nil }
      let captured = try #require(env.manager.connectionActivity)
      let connects = await env.ble.connectCount
      let disconnects = await env.ble.disconnectCount

      env.app.handleDeviceSelectionSheetDismissed()

      #expect(env.manager.connectionActivity == captured)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.app.statusPillState == .reconnecting)
      #expect(env.app.connectionUI.queuedSavedDeviceConnection == nil)
      #expect(!env.defaults.bool(forKey: PersistenceKeys.userExplicitlyDisconnected))
      #expect(await env.ble.connectCount == connects)
      #expect(await env.ble.disconnectCount == disconnects)
    }
  }

  @Test
  func `recovery status takes precedence over retired sync activity`() async throws {
    try await withHarness { env in
      _ = try await env.manager.connect(to: env.deviceID)
      await env.app.wireServicesIfConnected()
      let snapshot = try #require(env.manager.currentConnection)
      let coordinator = snapshot.services.syncCoordinator
      env.app.connectionUI.hideReadyToast()
      await coordinator.beginResyncActivity()
      #expect(env.app.connectionUI.syncActivityCount == 1)
      #expect(env.app.statusPillState == .syncing)

      await env.ble.simulateAutoReconnecting(deviceID: env.deviceID)
      try await waitUntil("physical loss must retire the syncing Link") {
        env.manager.currentConnection == nil && env.manager.connectionTeardownCount == 0
      }
      #expect(env.manager.connectionActivity?.requestID == snapshot.requestID)
      #expect(env.manager.connectionActivity?.phase == .reconnecting)
      #expect(env.app.statusPillState == .reconnecting)
      await coordinator.endResyncActivity(succeeded: false)

      #expect(env.app.connectionUI.syncActivityCount == 1)
      #expect(env.manager.connectionActivity?.phase == .reconnecting)
      #expect(env.app.statusPillState == .reconnecting)
    }
  }

  private func withHarness(
    foreignWiFiRadio: Bool = false, operation: (Harness) async throws -> Void
  ) async throws {
    let env = try Harness(paired: true, foreignWiFiRadio: foreignWiFiRadio)
    do {
      try await operation(env)
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
    let ble = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
    let wifi: ConnectionTestRadioTransport
    let defaults: UserDefaults
    private var workers: [Task<Void, Never>] = []
    let deviceID = UUID()
    private let suite = "test.recovery-callers.\(UUID())"

    init(paired: Bool = false, foreignWiFiRadio: Bool = false) throws {
      let defaults = try #require(UserDefaults(suiteName: suite))
      self.defaults = defaults
      wifi = ConnectionTestRadioTransport(
        publicKey: Data(repeating: foreignWiFiRadio ? 0xA2 : 0xA1, count: ProtocolLimits.publicKeySize)
      )
      defaults.set(false, forKey: AppStorageKey.hasCompletedOnboarding.rawValue)
      let container = try PersistenceStore.createContainer(inMemory: true)
      let picker = MockAccessorySetupKitService()
      let accessory = ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      if paired { picker.setPairedAccessories([accessory]) }
      picker.beforePickerResult = { [weak picker] in picker?.setPairedAccessories([accessory]) }
      picker.setPickerResult(.success(deviceID))
      picker.isSessionActive = false
      let ble = ble
      let wifi = wifi
      manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: ble,
        pairing: AccessorySetupPairingService(accessorySetupKit: picker), makeWiFiTransport: { wifi }
      )
      manager.otherAppWaitStrategyOverride = { _ in false }
      app = AppState(modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager)
    }

    func startWiFiSwitch() {
      workers.append(Task { await app.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "replacement.local") })
    }

    func finishWorkers() async {
      for worker in workers {
        await worker.value
      }
      workers.removeAll()
    }

    func close() async {
      await ble.releaseAllGates()
      await wifi.releaseAllGates()
      await finishWorkers()
      _ = await app.disconnect(reason: .userInitiated)
      try? await waitUntil("app pairing action should settle during cleanup") { !self.app.connectionUI.isBusy }
      app.tearDownAppStateSessionState()
      app.shutdown()
      await manager.retirementTask?.value
      UserDefaults().removePersistentDomain(forName: suite)
    }
  }
}
