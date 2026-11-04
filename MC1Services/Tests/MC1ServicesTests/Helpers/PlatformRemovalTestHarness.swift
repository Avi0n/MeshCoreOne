#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import Testing

@MainActor
final class PlatformRemovalTestHarness {
  @MainActor
  final class Gate {
    private(set) var entered = false
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false

    func wait() async {
      entered = true
      guard !isOpen else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      isOpen = true
      continuation?.resume()
      continuation = nil
    }
  }

  static let publicKey = Data(repeating: 0x73, count: 32)
  static let wifi = ConnectionMethod.wifi(host: "radio.test", port: 5123, displayName: nil)
  let deviceID = UUID()
  let radioID = UUID()
  let manager: ConnectionManager
  let stateMachine = MockBLEStateMachine()
  let radio = ConnectionTestRadioTransport(publicKey: PlatformRemovalTestHarness.publicKey)
  let ask = MockAccessorySetupKitService()
  let defaults: UserDefaults
  private let suiteName = "test.platform-removal.\(UUID().uuidString)"

  init(hasSystemRegistry: Bool) throws {
    defaults = try #require(UserDefaults(suiteName: suiteName))
    let container = try PersistenceStore.createContainer(inMemory: true)
    ask.isSessionActive = false
    let pairing: any DevicePairingService = hasSystemRegistry
      ? AccessorySetupPairingService(accessorySetupKit: ask) : BluetoothScanPairingService()
    manager = ConnectionManager(
      modelContainer: container, defaults: defaults, stateMachine: stateMachine,
      transport: radio, pairing: pairing
    )
  }

  func saveDevice(hasWiFi: Bool) async throws -> DeviceDTO {
    let saved = DeviceDTO.testDevice(id: deviceID, radioID: radioID, publicKey: Self.publicKey, isActive: false).copy {
      let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: $0.id, displayName: nil)
      $0.connectionMethods = hasWiFi ? [bluetooth, Self.wifi] : [bluetooth]
    }
    try await manager.persistenceStore.saveDevice(saved)
    manager.persistConnection(deviceID: saved.id, radioID: saved.radioID, deviceName: saved.nodeName)
    return saved
  }

  func failConnection() async throws -> UUID {
    await manager.wireTransportHandlers()
    await radio.setConnectError(BLEError.authenticationFailed)
    do {
      _ = try await manager.connect(to: deviceID, forceReconnect: true)
      Issue.record("The saved Bluetooth association must fail authentication")
      throw ConnectionError.notConnected
    } catch let failure as ConnectionRequestFailure {
      #expect(manager.isCurrent(failure))
      guard case BLEError.authenticationFailed = failure.underlying else { throw failure }
      #expect(await radio.connectCount == 1)
      #expect(manager.connectionState == .disconnected)
      return failure.requestID
    }
  }

  func seedBondVerification() async {
    await stateMachine.recordBondVerification(deviceID: deviceID, at: Date())
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceID)
  }

  func registerAccessory() {
    ask.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Saved radio")])
  }

  func requireRemoved(_ result: ConnectionRemovalResult) throws -> ConnectionLossReceipt {
    guard case let .removed(receipt) = result else {
      Issue.record("Removal of the current failed request must complete")
      throw ConnectionError.notConnected
    }
    #expect(manager.isCurrent(receipt))
    return receipt
  }

  func close() async {
    await radio.releaseAllGates()
    _ = await manager.disconnect()
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    defaults.removePersistentDomain(forName: suiteName)
  }
}
