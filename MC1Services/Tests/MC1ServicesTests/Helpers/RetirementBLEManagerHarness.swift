@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import Synchronization
import Testing

@MainActor
final class RetirementBLEManagerHarness {
  let ble: BLESetupRetirementHarness
  let wire: ConnectionTestRadioTransport
  let wifi = ConnectionTestRadioTransport(publicKey: Data(repeating: 0xB2, count: 32))
  let manager: ConnectionManager
  private let bridge: Bridge
  private let suite: String

  private init(ble: BLESetupRetirementHarness, maxChannels: UInt8) throws {
    self.ble = ble
    wire = ConnectionTestRadioTransport(maxChannels: maxChannels)
    suite = "test.manager-ble-retirement.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let store = try PersistenceStore.createContainer(inMemory: true)
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let wifi = wifi
    manager = ConnectionManager(
      modelContainer: store, defaults: defaults, stateMachine: ble.machine,
      transport: ble.adapter, pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
      makeWiFiTransport: { wifi }
    )
    manager.otherAppWaitStrategyOverride = { _ in false }
    bridge = Bridge(wire: wire, complete: { await ble.completeWrite() }, inject: { await ble.inject($0) })
  }

  static func make(maxChannels: UInt8 = 1) async throws -> RetirementBLEManagerHarness {
    let fixture = try await RetirementBLEManagerHarness(ble: BLESetupRetirementHarness.make(), maxChannels: maxChannels)
    await fixture.wire.setDeviceID(fixture.ble.peripheral.identifier)
    try await fixture.bridge.start()
    fixture.installWriteHandler(backpressureAfterChannelIndex: nil)
    return fixture
  }

  func installWriteHandler(backpressureAfterChannelIndex: UInt8?) {
    let bridge = bridge
    let native = ble.native
    let submit: @Sendable (Data) -> Void = { data in
      if data.first == CommandCode.importPrivateKey.rawValue {
        native.isReadyToWrite = true
      }
      bridge.submit(data)
    }
    native.setWriteHandler { data in
      if let backpressureAfterChannelIndex,
         data.first == CommandCode.getChannel.rawValue, data.dropFirst().first == backpressureAfterChannelIndex {
        native.isReadyToWrite = false
        native.setWriteHandler(submit)
      }
      submit(data)
    }
  }

  func connect() async throws {
    let machine = ble.machine
    let connect = Task {
      try await manager.connect(to: ble.peripheral.identifier, forceFullSync: true, forceReconnect: true)
    }
    do {
      try await ble.awaitCondition { await machine.currentPhase.name == "connecting" }
      await ble.finishDiscovery()
      _ = try await connect.value
    } catch {
      await ble.machine.shutdown()
      _ = await connect.result
      throw error
    }
  }

  func close() async {
    await wire.releaseAllGates()
    await wifi.releaseAllGates()
    ble.native.isReadyToWrite = true
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    ble.native.setWriteHandler(nil)
    await ble.machine.shutdown()
    await bridge.stop()
    defaultsCleanup()
  }

  private func defaultsCleanup() {
    UserDefaults().removePersistentDomain(forName: suite)
  }

  private final class Bridge: Sendable {
    private let wire: ConnectionTestRadioTransport
    private let complete: @Sendable () async -> Void
    private let inject: @Sendable (Data) async -> Void
    private let workers = Mutex<[Task<Void, Never>]>([])
    private let receive = Mutex<Task<Void, Never>?>(nil)

    init(wire: ConnectionTestRadioTransport, complete: @escaping @Sendable () async -> Void,
         inject: @escaping @Sendable (Data) async -> Void) {
      self.wire = wire
      self.complete = complete
      self.inject = inject
    }

    func start() async throws {
      try await wire.connect()
      let stream = await wire.receivedData
      let task = Task<Void, Never> { [inject] in
        for await packet in stream {
          await inject(packet)
        }
      }
      receive.withLock { $0 = task }
    }

    func submit(_ data: Data) {
      let task = Task<Void, Never> { [wire, complete] in
        await complete()
        try? await wire.send(data)
      }
      workers.withLock { $0.append(task) }
    }

    func stop() async {
      await wire.releaseAllGates()
      let active = workers.withLock { value in defer { value.removeAll() }; return value }
      for worker in active {
        await worker.value
      }
      await wire.disconnect()
      let task = receive.withLock { value in defer { value = nil }; return value }
      task?.cancel()
      await task?.value
    }
  }
}
