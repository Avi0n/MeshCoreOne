#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

#if canImport(UIKit)
  @Suite("AppState Remove and Retry")
  @MainActor
  struct RemoveFailedPairingTests {
    @Test(arguments: [false, true])
    func `declining removal preserves the current radio and failure episode`(hasWiFi: Bool) async throws {
      try await withHarness(hasWiFi: hasWiFi) { harness in
        harness.pairing.removeAccessoryError = NSError(
          domain: ASError.errorDomain,
          code: ASError.Code.userCancelled.rawValue
        )

        let operation = try #require(harness.appState.removeFailedPairingAndRetry())
        await operation.value

        #expect(harness.pairing.removeAccessoryCallCount == 1)
        #expect(harness.pairing.accessory(for: harness.device.id) != nil)
        #expect(await harness.transport.disconnectCount == 0)
        let saved = try #require(await harness.manager.persistenceStore.fetchDevice(id: harness.device.id))
        #expect(saved.radioID == harness.device.radioID)
        #expect(saved.connectionMethods == harness.device.connectionMethods)
        #expect(harness.manager.connectedDevice?.id == harness.device.id)
        #expect(harness.manager.connectedDevice?.connectionMethods == harness.device.connectionMethods)
        #expect(harness.manager.lastConnectedDeviceID == harness.device.id)
        #expect(harness.manager.lastConnectedRadioID == harness.device.radioID)
        #expect(harness.manager.connectionIntent.wantsConnection)
        #expect(harness.appState.connectionUI.failedPairingDeviceID == harness.device.id)
        #expect(!harness.appState.isRecoveringPairing)
        #expect(harness.pairing.showPickerCallCount == 0)
      }
    }

    @Test(arguments: [false, true])
    func `confirmed removal preserves the radio partition and opens the picker`(hasWiFi: Bool) async throws {
      try await withHarness(hasWiFi: hasWiFi) { harness in
        harness.pairing.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))
        let operation = try #require(harness.appState.removeFailedPairingAndRetry())
        await operation.value

        #expect(harness.pairing.removeAccessoryCallCount == 1)
        #expect(harness.pairing.accessory(for: harness.device.id) == nil)
        #expect(await harness.transport.disconnectCount == 1)
        let saved = try #require(await harness.manager.persistenceStore.fetchDevice(publicKey: harness.device.publicKey))
        #expect(saved.radioID == harness.device.radioID)
        if hasWiFi {
          #expect(saved.id == harness.device.id)
          #expect(saved.connectionMethods == harness.device.connectionMethods.filter(\.isWiFi))
        } else {
          #expect(saved.id != harness.device.id)
          #expect(saved.connectionMethods.isEmpty)
          #expect(harness.manager.lastConnectedDeviceID == nil)
        }
        #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
        #expect(!harness.appState.isRecoveringPairing)
        #expect(harness.pairing.showPickerCallCount == 1)
      }
    }

    @Test
    func `a new same-device failure episode survives an older successful removal`() async throws {
      try await withHarness(hasWiFi: false) { harness in
        let gate = AsyncStream<Void>.makeStream()
        defer { gate.continuation.finish() }
        harness.pairing.beforeRemovalResult = {
          for await _ in gate.stream {
            break
          }
        }
        let operation = try #require(harness.appState.removeFailedPairingAndRetry())
        do {
          try await waitUntil("removal confirmation should be pending") {
            harness.pairing.removeAccessoryCallCount == 1
          }
        } catch {
          gate.continuation.finish()
          await operation.value
          throw error
        }
        harness.appState.connectionUI.presentPairingFailure(
          .connectionFailed(deviceID: harness.device.id, underlying: BLEError.authenticationFailed)
        )
        let episodeID = harness.appState.connectionUI.pairingFailureEpisodeID
        gate.continuation.finish()
        await operation.value
        #expect(harness.appState.connectionUI.pairingFailureEpisodeID == episodeID)
        #expect(harness.appState.connectionUI.failedPairingDeviceID == harness.device.id)
        #expect(!harness.appState.isRecoveringPairing)
        #expect(harness.pairing.showPickerCallCount == 0)
      }
    }

    @MainActor
    private struct Harness {
      let appState: AppState
      let device: DeviceDTO
      let transport: ConnectionTestRadioTransport
      let pairing: MockAccessorySetupKitService
      let suiteName: String

      var manager: ConnectionManager {
        appState.connectionManager
      }

      func cleanup() async {
        await appState.disconnect(reason: .userInitiated)
        appState.shutdown()
        UserDefaults().removePersistentDomain(forName: suiteName)
      }
    }

    private func withHarness(hasWiFi: Bool, _ body: @MainActor (Harness) async throws -> Void) async throws {
      let harness = try await makeHarness(hasWiFi: hasWiFi)
      do {
        try await body(harness)
      } catch {
        await harness.cleanup()
        throw error
      }
      await harness.cleanup()
    }

    private func makeHarness(hasWiFi: Bool) async throws -> Harness {
      let suiteName = "test.remove-retry.\(UUID().uuidString)"
      let defaults = try #require(UserDefaults(suiteName: suiteName))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let pairing = MockAccessorySetupKitService()
      let deviceID = UUID()
      var methods: [ConnectionMethod] = [.bluetooth(peripheralUUID: deviceID, displayName: "Radio")]
      if hasWiFi {
        methods.append(.wifi(host: "radio.test", port: 5678, displayName: "Radio"))
      }
      let device = DeviceDTO(from: Device(
        id: deviceID,
        radioID: UUID(),
        publicKey: Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize),
        nodeName: "Radio",
        isActive: true,
        connectionMethods: methods
      ))
      let transport = ConnectionTestRadioTransport(publicKey: device.publicKey)
      let manager = ConnectionManager(
        modelContainer: container,
        defaults: defaults,
        stateMachine: MockBLEStateMachine(),
        transport: transport,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing)
      )
      try await manager.persistenceStore.saveDevice(device)
      pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: device.id, displayName: device.nodeName)])
      let appState = AppState(
        modelContainer: container,
        isPlaceholder: true,
        defaults: defaults,
        injectedConnectionManager: manager
      )
      do {
        _ = try await manager.connect(to: device.id)
        let connected = try #require(manager.connectedDevice)
        #expect(connected.radioID == device.radioID)
        appState.connectionUI.failedPairingDeviceID = connected.id
        return Harness(appState: appState, device: connected, transport: transport, pairing: pairing, suiteName: suiteName)
      } catch {
        await appState.disconnect(reason: .userInitiated)
        appState.shutdown()
        defaults.removePersistentDomain(forName: suiteName)
        throw error
      }
    }
  }
#endif
