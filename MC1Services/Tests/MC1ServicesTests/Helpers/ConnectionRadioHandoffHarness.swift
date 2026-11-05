import Foundation
@testable import MC1Services
import MeshCore
import Testing

@MainActor
final class ConnectionRadioHandoffHarness {
  let manager: ConnectionManager
  let bluetooth: ConnectionTestRadioTransport
  let wifi: ConnectionTestRadioTransport
  let pairing: MockAccessorySetupKitService
  let stateMachine: MockBLEStateMachine
  let deviceA: DeviceDTO
  let deviceB: DeviceDTO
  private let suiteName: String
  private var joins: [() async -> Void] = []
  private var cancellations: [() -> Void] = []
  private var nodeKeys: [Data] = []

  private init(
    manager: ConnectionManager, bluetooth: ConnectionTestRadioTransport, wifi: ConnectionTestRadioTransport,
    pairing: MockAccessorySetupKitService, stateMachine: MockBLEStateMachine,
    deviceA: DeviceDTO, deviceB: DeviceDTO, suiteName: String
  ) {
    self.manager = manager
    self.bluetooth = bluetooth
    self.wifi = wifi
    self.pairing = pairing
    self.stateMachine = stateMachine
    self.deviceA = deviceA
    self.deviceB = deviceB
    self.suiteName = suiteName
  }

  static func make(wifiIsSameRadio: Bool = true, maxChannels: UInt8 = 1) async throws -> ConnectionRadioHandoffHarness {
    let suite = "test.radio-handoff.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let container = try PersistenceStore.createContainer(inMemory: true)
    let keyA = Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize)
    let keyB = Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize)
    let bluetooth = ConnectionTestRadioTransport(publicKey: keyA, maxChannels: maxChannels)
    let wifi = ConnectionTestRadioTransport(publicKey: wifiIsSameRadio ? keyA : keyB, maxChannels: maxChannels)
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let stateMachine = MockBLEStateMachine()
    let manager = ConnectionManager(
      modelContainer: container, defaults: defaults, stateMachine: stateMachine, transport: bluetooth,
      pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { wifi }
    )
    let deviceA = DeviceDTO.testDevice(radioID: UUID(), publicKey: keyA, firmwareVersion: 14).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: $0.id, displayName: nil)]
    }
    let deviceB = DeviceDTO.testDevice(radioID: UUID(), publicKey: keyB, firmwareVersion: 14, isActive: false).copy {
      $0.connectionMethods = [.wifi(host: "radio.local", port: 4242, displayName: nil)]
    }
    try await manager.persistenceStore.saveDevice(deviceA)
    try await manager.persistenceStore.saveDevice(deviceB)
    return ConnectionRadioHandoffHarness(
      manager: manager, bluetooth: bluetooth, wifi: wifi, pairing: pairing, stateMachine: stateMachine,
      deviceA: deviceA, deviceB: deviceB, suiteName: suite
    )
  }

  func connectBluetooth() async throws {
    _ = try await manager.connect(to: deviceA.id, forceReconnect: true)
    #expect(manager.connectionState == .ready)
    #expect(manager.connectedDevice?.radioID == deviceA.radioID)
  }

  func addMembership(radioID: UUID, connected: Bool) async throws -> RemoteNodeSessionDTO {
    let key = Data(UUID().uuidString.utf8.prefix(ProtocolLimits.publicKeySize))
    nodeKeys.append(key)
    try await KeychainService.shared.storePassword("handoff-test", forNodeKey: key)
    let membership = RemoteNodeSessionDTO.testSession(
      radioID: radioID, publicKey: key, isConnected: connected, permissionLevel: .readWrite
    )
    let contact = ContactDTO.testContact(radioID: radioID, publicKey: key, typeRawValue: ContactType.room.rawValue)
    try await manager.persistenceStore.saveContact(contact)
    let frame = contact.toContactFrame().toMeshContact()
    for peer in [bluetooth, wifi] {
      let matchesA = radioID == deviceA.radioID && peer.publicKey == deviceA.publicKey
      let matchesB = radioID == deviceB.radioID && peer.publicKey == deviceB.publicKey
      if matchesA || matchesB { await peer.setContact(frame) }
    }
    try await manager.persistenceStore.saveRemoteNodeSessionDTO(membership)
    return membership
  }

  func isConnected(_ membership: RemoteNodeSessionDTO) async throws -> Bool {
    try #require(try await manager.persistenceStore.fetchRemoteNodeSession(id: membership.id)).isConnected
  }

  func track<Success: Sendable, Failure: Error>(_ task: Task<Success, Failure>) -> Task<Success, Failure> {
    cancellations.append { task.cancel() }
    joins.append { _ = await task.result }
    return task
  }

  func finish() async {
    await bluetooth.releaseAllGates()
    await wifi.releaseAllGates()
    for cancel in cancellations {
      cancel()
    }
    _ = await manager.disconnect(reason: .userInitiated)
    for join in joins {
      await join()
    }
    for key in nodeKeys {
      try? await KeychainService.shared.deletePassword(forNodeKey: key)
    }
    UserDefaults().removePersistentDomain(forName: suiteName)
    #expect(manager.services == nil)
    #expect(await bluetooth.isConnected == false)
    #expect(await wifi.isConnected == false)
  }

  static func withHarness(
    wifiIsSameRadio: Bool = true,
    maxChannels: UInt8 = 1,
    _ body: (ConnectionRadioHandoffHarness) async throws -> Void
  ) async throws {
    let harness = try await make(wifiIsSameRadio: wifiIsSameRadio, maxChannels: maxChannels)
    do { try await body(harness) } catch { await harness.finish(); throw error }
    await harness.finish()
  }
}
