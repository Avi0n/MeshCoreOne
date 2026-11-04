@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import ObjectiveC
import os

/// Drives the production connect/discovery/write path without priming BLEPhase.
/// Native CB objects still need runtime allocation and process-lifetime retention.
final class BLETransportHarness: @unchecked Sendable {
  let central: CBCentralManager
  let peripheral: CBPeripheral
  let service: CBMutableService
  let tx: CBMutableCharacteristic
  let rx: CBMutableCharacteristic
  let deviceID: UUID

  private struct State {
    var peripheralState: CBPeripheralState = .connected
    var systemConnected = false
    var writeHandler: (@Sendable (Data) -> Void)?
    var power: CBManagerState = .poweredOn
    var isReadyToWrite = false
    var peripheralDelegate: (any CBPeripheralDelegate)?
    var connects = 0
    var cancellations = 0
    var serviceDiscoveries = 0
    var characteristicDiscoveries = 0
    var subscriptions = 0
    var writes: [Data] = []
    var automaticBridge: BLEDelegateHandler?
    var onWrite: (@Sendable (Data) -> [Data])?
    var automaticAcknowledgements = 0
    var peers: [UUID: BLETransportHarness] = [:]
  }

  private let state = OSAllocatedUnfairLock(initialState: State())

  private final class Registration {
    weak var harness: BLETransportHarness?
    init(_ harness: BLETransportHarness) {
      self.harness = harness
    }
  }

  /// The lock protects retention and lookup; native objects are used only by the harness.
  private struct Registry: @unchecked Sendable {
    var registrations: [ObjectIdentifier: Registration] = [:]
    var retained: [NSObject] = []
  }

  private static let registry = OSAllocatedUnfairLock(initialState: Registry())

  init(deviceID: UUID = UUID(), supportsWriteWithoutResponse: Bool = false) {
    self.deviceID = deviceID
    // swiftlint:disable:next force_cast
    central = class_createInstance(Central.self, 0) as! Central
    // swiftlint:disable:next force_cast
    peripheral = class_createInstance(Peripheral.self, 0) as! Peripheral
    tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic), properties: supportsWriteWithoutResponse ? [.write, .writeWithoutResponse] : [.write],
      value: nil, permissions: [.writeable]
    )
    rx = NotifyingCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic), properties: [.notify],
      value: nil, permissions: [.readable]
    )
    service = CBMutableService(type: CBUUID(string: BLEServiceUUID.nordicUART), primary: true)
    service.characteristics = [tx, rx]
    Self.registry.withLock {
      $0.registrations[ObjectIdentifier(central)] = Registration(self)
      $0.registrations[ObjectIdentifier(peripheral)] = Registration(self)
      $0.retained += [central, peripheral]
    }
  }

  var peripheralState: CBPeripheralState {
    get { state.withLock { $0.peripheralState } }
    set { state.withLock { $0.peripheralState = newValue } }
  }

  var systemConnected: Bool {
    get { state.withLock { $0.systemConnected } }
    set { state.withLock { $0.systemConnected = newValue } }
  }

  func setWriteHandler(_ handler: (@Sendable (Data) -> Void)?) {
    state.withLock { $0.writeHandler = handler }
  }

  var power: CBManagerState {
    get { state.withLock { $0.power } }
    set { state.withLock { $0.power = newValue } }
  }

  var isReadyToWrite: Bool {
    get { state.withLock { $0.isReadyToWrite } }
    set { state.withLock { $0.isReadyToWrite = newValue } }
  }

  var writePayloads: [Data] {
    state.withLock { $0.writes }
  }

  var connectCount: Int {
    state.withLock { $0.connects }
  }

  var cancellationCount: Int {
    state.withLock { $0.cancellations }
  }

  var serviceDiscoveryCount: Int {
    state.withLock { $0.serviceDiscoveries }
  }

  var characteristicDiscoveryCount: Int {
    state.withLock { $0.characteristicDiscoveries }
  }

  var subscriptionCount: Int {
    state.withLock { $0.subscriptions }
  }

  var automaticAcknowledgementCount: Int {
    state.withLock { $0.automaticAcknowledgements }
  }

  func enableAutomaticCallbacks(_ bridge: BLEDelegateHandler, onWrite: @escaping @Sendable (Data) -> [Data]) {
    state.withLock {
      $0.automaticBridge = bridge
      $0.onWrite = onWrite
    }
  }

  private var automaticBridge: BLEDelegateHandler? {
    state.withLock { $0.automaticBridge }
  }

  func registerPeer(_ peer: BLETransportHarness) {
    state.withLock { $0.peers[peer.deviceID] = peer }
  }

  /// An empty radio with one unused channel slot and no queued messages.
  static func emptyRadioFrames(for data: Data) -> [Data] {
    switch data.first {
    case CommandCode.appStart.rawValue:
      var response = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      response.append(Data(repeating: 1, count: 32))
      response.append(Data(repeating: 0, count: 12))
      for value: UInt32 in [915_000, 125_000] {
        response.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      response.append(contentsOf: [7, 5])
      return [response]
    case CommandCode.deviceQuery.rawValue:
      var response = Data([ResponseCode.deviceInfo.rawValue, 14, 1, 1])
      response.append(Data(repeating: 0, count: 76))
      return [response]
    case CommandCode.getAutoAddConfig.rawValue:
      return [Data([ResponseCode.autoAddConfig.rawValue, 0])]
    case CommandCode.getContacts.rawValue:
      return [Data([ResponseCode.contactStart.rawValue, 0, 0, 0, 0]), Data([ResponseCode.contactEnd.rawValue, 0, 0, 0, 0])]
    case CommandCode.getChannel.rawValue:
      let index = data.count > 1 ? data[1] : 0
      return [Data([ResponseCode.channelInfo.rawValue, index]) + Data(repeating: 0, count: 48)]
    case CommandCode.getMessage.rawValue:
      return [Data([ResponseCode.noMoreMessages.rawValue])]
    case CommandCode.getTime.rawValue:
      var timestamp = UInt32(Date().timeIntervalSince1970).littleEndian
      return [Data([ResponseCode.currentTime.rawValue]) + withUnsafeBytes(of: &timestamp) { Data($0) }]
    case CommandCode.setTime.rawValue:
      return [Data([ResponseCode.ok.rawValue])]
    case CommandCode.getBattery.rawValue:
      return [Data([ResponseCode.battery.rawValue, 0x74, 0x0E])]
    default:
      return [Data([ResponseCode.error.rawValue, 1])]
    }
  }

  private func issueWrite(_ data: Data, type: CBCharacteristicWriteType) {
    let callback = state.withLock { current -> (@Sendable (Data) -> [Data])? in
      current.writes.append(data)
      return current.onWrite
    }
    state.withLock { $0.writeHandler }?(data)
    guard let bridge = automaticBridge else { return }
    if type == .withResponse {
      state.withLock { $0.automaticAcknowledgements += 1 }
      bridge.peripheral(peripheral, didWriteValueFor: tx, error: nil)
    }
    for frame in callback?(data) ?? [] {
      rx.value = frame
      bridge.peripheral(peripheral, didUpdateValueFor: rx, error: nil)
    }
  }

  func connect(_ machine: BLEStateMachine) async throws -> AsyncStream<Data> {
    let pending = Task { try await machine.connect(to: deviceID) }
    guard await awaitCondition({ self.connectCount == 1 }) else {
      await machine.shutdown()
      _ = try? await pending.value
      throw BLEError.connectionFailed("Harness did not issue connect")
    }
    let bridge = await machine.delegateHandler
    bridge.centralManager(central, didConnect: peripheral)
    guard await awaitCondition({ self.serviceDiscoveryCount == 1 }) else {
      await machine.shutdown()
      _ = try? await pending.value
      throw BLEError.connectionFailed("Harness did not discover services")
    }
    bridge.peripheral(peripheral, didDiscoverServices: nil)
    guard await awaitCondition({ self.characteristicDiscoveryCount == 1 }) else {
      await machine.shutdown()
      _ = try? await pending.value
      throw BLEError.connectionFailed("Harness did not discover characteristics")
    }
    bridge.peripheral(peripheral, didDiscoverCharacteristicsFor: service, error: nil)
    guard await awaitCondition({ self.subscriptionCount == 1 }) else {
      await machine.shutdown()
      _ = try? await pending.value
      throw BLEError.connectionFailed("Harness did not subscribe")
    }
    bridge.peripheral(peripheral, didUpdateNotificationStateFor: rx, error: nil)
    return try await pending.value
  }

  func acknowledge(_ machine: BLEStateMachine, error: Error? = nil) async {
    let bridge = await machine.delegateHandler
    bridge.peripheral(peripheral, didWriteValueFor: tx, error: error)
  }

  func awaitCondition(_ condition: @escaping @Sendable () async -> Bool) async -> Bool {
    do {
      try await waitUntil { await condition() }
      return true
    } catch {
      return false
    }
  }

  private static func harness(for object: NSObject) -> BLETransportHarness? {
    let identity = ObjectIdentifier(object)
    return registry.withLock { $0.registrations[identity]?.harness }
  }

  private final class Central: CBCentralManager, @unchecked Sendable {
    override var state: CBManagerState {
      BLETransportHarness.harness(for: self)?.power ?? .unknown
    }

    override func retrievePeripherals(withIdentifiers identifiers: [UUID]) -> [CBPeripheral] {
      guard let harness = BLETransportHarness.harness(for: self) else { return [] }
      return identifiers.compactMap { id in
        if id == harness.deviceID { return harness.peripheral }
        return harness.state.withLock { $0.peers[id]?.peripheral }
      }
    }

    override func retrieveConnectedPeripherals(withServices serviceUUIDs: [CBUUID]) -> [CBPeripheral] {
      guard let harness = BLETransportHarness.harness(for: self),
            harness.systemConnected, harness.peripheralState == .connected else { return [] }
      return [harness.peripheral]
    }

    override func connect(_ peripheral: CBPeripheral, options: [String: Any]? = nil) {
      guard let harness = BLETransportHarness.harness(for: self) else { return }
      harness.state.withLock { $0.connects += 1 }
      harness.automaticBridge?.centralManager(self, didConnect: peripheral)
    }

    override func cancelPeripheralConnection(_ peripheral: CBPeripheral) {
      BLETransportHarness.harness(for: self)?.state.withLock { $0.cancellations += 1 }
    }

    override func stopScan() {}
  }

  private final class Peripheral: CBPeripheral, @unchecked Sendable {
    override var identifier: UUID {
      BLETransportHarness.harness(for: self)!.deviceID
    }

    override var name: String? {
      "Harness"
    }

    override var state: CBPeripheralState {
      BLETransportHarness.harness(for: self)?.peripheralState ?? .disconnected
    }

    override var canSendWriteWithoutResponse: Bool {
      BLETransportHarness.harness(for: self)?.isReadyToWrite ?? false
    }

    override var services: [CBService]? {
      BLETransportHarness.harness(for: self).map { [$0.service] }
    }

    override var delegate: (any CBPeripheralDelegate)? {
      get { BLETransportHarness.harness(for: self)?.state.withLock { $0.peripheralDelegate } }
      set { BLETransportHarness.harness(for: self)?.state.withLock { $0.peripheralDelegate = newValue } }
    }

    override func discoverServices(_ serviceUUIDs: [CBUUID]?) {
      guard let harness = BLETransportHarness.harness(for: self) else { return }
      harness.state.withLock { $0.serviceDiscoveries += 1 }
      harness.automaticBridge?.peripheral(self, didDiscoverServices: nil)
    }

    override func discoverCharacteristics(_ characteristicUUIDs: [CBUUID]?, for service: CBService) {
      guard let harness = BLETransportHarness.harness(for: self) else { return }
      harness.state.withLock { $0.characteristicDiscoveries += 1 }
      harness.automaticBridge?.peripheral(self, didDiscoverCharacteristicsFor: service, error: nil)
    }

    override func setNotifyValue(_ enabled: Bool, for characteristic: CBCharacteristic) {
      guard let harness = BLETransportHarness.harness(for: self) else { return }
      harness.state.withLock { $0.subscriptions += 1 }
      harness.automaticBridge?.peripheral(self, didUpdateNotificationStateFor: characteristic, error: nil)
    }

    override func writeValue(_ data: Data, for characteristic: CBCharacteristic, type: CBCharacteristicWriteType) {
      BLETransportHarness.harness(for: self)?.issueWrite(data, type: type)
    }

    override func readRSSI() {}
  }

  private final class NotifyingCharacteristic: CBMutableCharacteristic {
    override var isNotifying: Bool {
      true
    }
  }
}
