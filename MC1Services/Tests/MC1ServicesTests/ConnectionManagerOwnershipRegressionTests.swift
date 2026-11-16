import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Connection ownership regressions")
@MainActor
struct ConnectionManagerOwnershipRegressionTests {
  @Test(.timeLimit(.minutes(1)))
  func `explicit disconnect supersedes launch fallback while BLE is dialing`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let transport = ConnectionTestRadioTransport()
    await transport.holdConnect()
    await transport.setConnectError(BLEError.connectionFailed("held launch failed"))
    let wifi = ConnectionTestRadioTransport()
    let suite = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { UserDefaults().removePersistentDomain(forName: suite) }
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: MockBLEStateMachine(),
      transport: transport,
      pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
      makeWiFiTransport: { wifi }
    )
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [
        .wifi(host: "10.0.0.8", port: 5000, displayName: nil),
        .bluetooth(peripheralUUID: $0.id, displayName: nil)
      ]
    }
    try await manager.persistenceStore.saveDevice(device)
    manager.testLastConnectedDeviceID = device.id

    let launch = Task { await manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id) }
    do {
      try await waitUntil("launch should reach the physical BLE dial") { await transport.connectIsHeld }
      let disconnect = Task { await manager.disconnect(reason: .userInitiated) }
      try await waitUntil("disconnect should revoke launch publication") { manager.connectionState == .disconnected }
      await transport.releaseConnect()
      _ = await disconnect.value
      await launch.value

      #expect(await wifi.connectCount == 0, "A superseded launch must not start its fallback")
      #expect(manager.connectionIntent.isUserDisconnected)
      #expect(manager.connectionState == .disconnected)
      #expect(!manager.isReconnectionWatchdogRunning)
    } catch {
      await transport.releaseConnect()
      launch.cancel()
      await launch.value
      throw error
    }
    manager.stopReconnectionWatchdog()
  }

  @Test(.timeLimit(.minutes(1)))
  func `successful BLE resync runs the timezone step through the production retry path`() async throws {
    let transport = ConnectionTestRadioTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    let services = try await ServiceContainer.forTesting(session: session)
    let (manager, _) = try ConnectionManager.createForTesting()
    let device = DeviceDTO.testDevice(firmwareVersion: 14)
    try await services.dataStore.saveDevice(device)
    manager.setTestState(
      connectionState: .syncing,
      services: services,
      session: session,
      connectedDevice: device,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    await services.syncCoordinator.setPerformResyncOverride { _, _ in
      FullSyncResult(contacts: .clean, channels: .clean, messages: .clean)
    }
    var completed = false
    manager.onDeviceSynced = { _, _ in completed = true }
    manager.startResyncLoop(radioID: device.radioID, services: services)
    do {
      try await waitUntil(timeout: .seconds(10), "production resync should complete") { completed }
      let commands = await transport.cliCommands.map { ConnectionTestRadioTransport.splitCLICommand($0).command }
      #expect(commands.contains("get tz.offset"), "BLE recovery must run the same timezone work as initial success")
      #expect(manager.connectionState == .ready)
    } catch {
      manager.cancelResyncLoop()
      await session.stop()
      await services.tearDown()
      throw error
    }
    manager.cancelResyncLoop()
    await session.stop()
    await services.tearDown()
  }
}
