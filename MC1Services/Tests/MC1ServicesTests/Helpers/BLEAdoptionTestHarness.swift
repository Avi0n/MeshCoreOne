@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@MainActor
final class BLEAdoptionTestHarness {
  let ble: BLESetupRetirementHarness
  let transport: BLEAdoptionOriginTransport
  let manager: ConnectionManager
  let firmware: ConnectionTestRadioTransport
  private let suite: String
  private let writes: AsyncStream<Data>.Continuation
  private let writeWorker: Task<Void, Never>
  private let replyWorker: Task<Void, Never>
  private(set) var handledEvents: [BLELifecycleEvent] = []

  private init(
    ble: BLESetupRetirementHarness, transport: BLEAdoptionOriginTransport, manager: ConnectionManager,
    firmware: ConnectionTestRadioTransport, suite: String, writes: AsyncStream<Data>.Continuation,
    writeWorker: Task<Void, Never>, replyWorker: Task<Void, Never>
  ) {
    self.ble = ble
    self.transport = transport
    self.manager = manager
    self.firmware = firmware
    self.suite = suite
    self.writes = writes
    self.writeWorker = writeWorker
    self.replyWorker = replyWorker
    manager.testBLELifecycleEventHandled = { [weak self] in self?.handledEvents.append($0) }
  }

  static func make() async throws -> BLEAdoptionTestHarness {
    let ble = await BLESetupRetirementHarness.make()
    ble.native.peripheralState = .connected
    ble.native.systemConnected = true
    let transport = BLEAdoptionOriginTransport(base: ble.adapter)
    let firmware = ConnectionTestRadioTransport()
    try await firmware.connect()
    let suite = "test.adoption.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let manager = try ConnectionManager(
      modelContainer: PersistenceStore.createContainer(inMemory: true), defaults: defaults,
      stateMachine: ble.machine, transport: transport, pairing: AccessorySetupPairingService(accessorySetupKit: pairing)
    )
    let saved = DeviceDTO.testDevice(id: ble.peripheral.identifier, publicKey: firmware.publicKey, isActive: false).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: ble.peripheral.identifier, displayName: nil)]
    }
    try await manager.persistenceStore.saveDevice(saved)
    manager.persistConnection(deviceID: saved.id, radioID: saved.radioID, deviceName: saved.nodeName)
    await manager.wireTransportHandlers()
    let (physicalWrites, continuation) = AsyncStream.makeStream(of: Data.self)
    ble.native.setWriteHandler { continuation.yield($0) }
    let writeWorker = Task {
      for await data in physicalWrites {
        try? await firmware.send(data)
        await ble.completeWrite()
      }
    }
    let replies = await firmware.receivedData
    let replyWorker = Task {
      for await reply in replies {
        await ble.inject(reply)
      }
    }
    return BLEAdoptionTestHarness(
      ble: ble, transport: transport, manager: manager, firmware: firmware, suite: suite,
      writes: continuation, writeWorker: writeWorker, replyWorker: replyWorker
    )
  }

  func completeAdoptionDiscovery() async {
    await ble.machine.handleDidDiscoverServices(ble.peripheral, error: nil)
    await ble.machine.handleDidDiscoverCharacteristics(ble.peripheral, service: ble.native.service, error: nil)
    await ble.machine.handleDidUpdateNotificationState(ble.peripheral, characteristic: ble.native.rx, error: nil)
  }

  func awaitCondition(_ predicate: @escaping @MainActor () async -> Bool) async throws {
    guard await observedBeforeDeadline(predicate) else { throw BLESetupRetirementHarness.WaitError.expired }
  }

  func observedBeforeDeadline(_ predicate: @escaping @MainActor () async -> Bool) async -> Bool {
    do {
      try await waitUntil { await predicate() }
      return true
    } catch {
      return false
    }
  }

  func close() async {
    await transport.releaseOrigin()
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    manager.testBLELifecycleEventHandled = nil
    ble.native.setWriteHandler(nil)
    writes.finish()
    await writeWorker.value
    await firmware.disconnect()
    await replyWorker.value
    await ble.machine.shutdown()
    UserDefaults().removePersistentDomain(forName: suite)
  }
}
