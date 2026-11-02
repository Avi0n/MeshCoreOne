// BLEStateMachine+CBDelegate.swift
@preconcurrency import CoreBluetooth
import Foundation
import os

// MARK: - Delegate Handler

/// Validates native callback origins before forwarding control events to the actor.
/// Receive values yield under the ingress lock on the serial central queue to preserve order.
final class BLEDelegateHandler: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
  weak var stateMachine: BLEStateMachine?

  private let logger = PersistentLogger(subsystem: "com.mc1", category: "BLEDelegateHandler")

  /// Lock-protected continuation for yielding received data directly.
  /// Using OSAllocatedUnfairLock ensures thread-safe access from the CBCentralManager queue.
  private struct Ingress: Sendable {
    let continuation: AsyncStream<Data>.Continuation
    let peripheral: ObjectIdentifier
    let characteristic: ObjectIdentifier
    var diagnostics: BLEIngressDiagnostics
  }

  private let dataContinuationLock = OSAllocatedUnfairLock<Ingress?>(initialState: nil)
  private static let ingressReportInterval = 64

  private struct IssuedWrite: Sendable {
    let sequence: UInt64
    let peripheral: ObjectIdentifier
    let characteristic: ObjectIdentifier
  }

  /// Validate native origin before consuming the issued-write order.
  private let issuedWriteSequencesLock = OSAllocatedUnfairLock<[IssuedWrite]>(initialState: [])

  func recordIssuedWriteSequence(_ sequence: UInt64, peripheral: CBPeripheral, characteristic: CBCharacteristic) {
    let write = IssuedWrite(
      sequence: sequence, peripheral: ObjectIdentifier(peripheral), characteristic: ObjectIdentifier(characteristic)
    )
    issuedWriteSequencesLock.withLock { $0.append(write) }
  }

  func clearIssuedWriteSequences() {
    issuedWriteSequencesLock.withLock { $0.removeAll() }
  }

  var ingressDiagnostics: BLEIngressDiagnostics? {
    dataContinuationLock.withLock { $0?.diagnostics }
  }

  func setDataContinuation(
    _ continuation: AsyncStream<Data>.Continuation,
    peripheral: CBPeripheral,
    characteristic: CBCharacteristic,
    instanceID: String,
    generation: UInt64,
    streamSequence: UInt64,
    binding: String?
  ) {
    let ingress = Ingress(
      continuation: continuation,
      peripheral: ObjectIdentifier(peripheral),
      characteristic: ObjectIdentifier(characteristic),
      diagnostics: BLEIngressDiagnostics(
        deviceID: peripheral.identifier, instanceID: instanceID, connectionGeneration: generation,
        streamSequence: streamSequence, binding: binding
      )
    )
    let previous = dataContinuationLock.withLock { current in
      defer { current = ingress }
      return current?.diagnostics
    }
    reportFinishedIngress(previous)
    logger.info("[BLE] Ingress installed \(ingress.diagnostics.context)")
  }

  func clearDataContinuation() {
    let previous = dataContinuationLock.withLock { current in
      defer { current = nil }
      return current?.diagnostics
    }
    reportFinishedIngress(previous)
  }

  private func reportFinishedIngress(_ diagnostics: BLEIngressDiagnostics?) {
    guard let diagnostics else { return }
    logger.info("[BLE] Ingress finished \(diagnostics.context) accepted=\(diagnostics.acceptedFrames) dropped=\(diagnostics.droppedFrames) rejected=\(diagnostics.rejectedFrames) terminated=\(diagnostics.terminatedFrames)")
  }

  private func generation(for peripheral: CBPeripheral) -> UInt64? {
    let origin = ObjectIdentifier(peripheral)
    return dataContinuationLock.withLock { ingress in
      guard ingress?.peripheral == origin else { return nil }
      return ingress?.diagnostics.connectionGeneration
    }
  }

  // MARK: - CBCentralManagerDelegate

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleCentralManagerDidUpdateState(central.state) }
  }

  func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    // Extract peripheral synchronously before crossing actor boundary
    guard let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
          let peripheral = peripherals.first else {
      return
    }
    _ = restoreState(from: peripheral)
  }

  func restoreState(from peripheral: CBPeripheral) -> Task<Void, Never>? {
    guard let sm = stateMachine else { return nil }
    return Task { await sm.handleWillRestoreState(peripheral) }
  }

  func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                      advertisementData: [String: Any], rssi RSSI: NSNumber) {
    guard let sm = stateMachine else { return }
    let peripheralID = peripheral.identifier
    let rssiValue = RSSI.intValue
    // Prefer the advertised local name; it is present before a connection is established
    // and fresher than the cached `peripheral.name`.
    let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
    Task { await sm.handleDidDiscoverPeripheral(peripheralID: peripheralID, name: name, rssi: rssiValue) }
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidConnect(peripheral) }
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidFailToConnect(peripheral, error: error) }
  }

  func centralManager(
    _ central: CBCentralManager,
    didDisconnectPeripheral peripheral: CBPeripheral,
    timestamp: CFAbsoluteTime,
    isReconnecting: Bool,
    error: Error?
  ) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidDisconnect(peripheral, timestamp: timestamp, isReconnecting: isReconnecting, error: error) }
  }

  // MARK: - CBPeripheralDelegate

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidDiscoverServices(peripheral, error: error) }
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidDiscoverCharacteristics(peripheral, service: service, error: error) }
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    guard let sm = stateMachine else { return }
    Task { await sm.handleDidUpdateNotificationState(peripheral, characteristic: characteristic, error: error) }
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    // Yield data directly to preserve ordering from the serial CBCentralManager queue.
    // Do not spawn a Task here - that breaks ordering guarantees.
    if let error {
      logger.warning("[BLE] didUpdateValueFor error: \(peripheral.identifier.uuidString.prefix(8)), char: \(characteristic.uuid.uuidString.prefix(8)), error: \(error.localizedDescription)")
      return
    }
    guard let data = characteristic.value, !data.isEmpty else {
      logger.debug("[BLE] didUpdateValueFor: empty data from \(peripheral.identifier.uuidString.prefix(8)), char: \(characteristic.uuid.uuidString.prefix(8))")
      return
    }
    let origin = ObjectIdentifier(peripheral)
    let characteristicOrigin = ObjectIdentifier(characteristic)
    let report = dataContinuationLock.withLock { current -> String? in
      guard var ingress = current else { return nil }
      defer { current = ingress }
      guard ingress.peripheral == origin, ingress.characteristic == characteristicOrigin else {
        ingress.diagnostics.rejectedFrames += 1
        let rejected = ingress.diagnostics.rejectedFrames
        guard rejected == 1 || rejected.isMultiple(of: Self.ingressReportInterval) else { return nil }
        return "[BLE] Ingress rejected origin \(ingress.diagnostics.context) rejected=\(rejected)"
      }
      switch ingress.continuation.yield(data) {
      case .enqueued:
        ingress.diagnostics.acceptedFrames += 1
        return nil
      case .dropped:
        ingress.diagnostics.droppedFrames += 1
        let dropped = ingress.diagnostics.droppedFrames
        guard dropped == 1 || dropped.isMultiple(of: Self.ingressReportInterval) else { return nil }
        return "[BLE] Ingress dropped frame \(ingress.diagnostics.context) reason=buffer-full dropped=\(dropped)"
      case .terminated:
        ingress.diagnostics.terminatedFrames += 1
        let terminated = ingress.diagnostics.terminatedFrames
        guard terminated == 1 || terminated.isMultiple(of: Self.ingressReportInterval) else { return nil }
        return "[BLE] Ingress terminated frame \(ingress.diagnostics.context) reason=stream-terminated terminated=\(terminated)"
      @unknown default:
        return nil
      }
    }
    if let report { logger.warning(report) }
  }

  func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
    _ = readRSSI(from: peripheral, RSSI: RSSI, error: error)
  }

  func readRSSI(from peripheral: CBPeripheral, RSSI: NSNumber, error: Error?) -> Task<Void, Never>? {
    guard let sm = stateMachine, let generation = generation(for: peripheral) else { return nil }
    return Task { await sm.handleDidReadRSSI(peripheral, RSSI: RSSI, error: error, generation: generation) }
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    guard let sm = stateMachine else { return }
    let origin = ObjectIdentifier(peripheral)
    let characteristicOrigin = ObjectIdentifier(characteristic)
    let (sequence, rejectedOrigin) = issuedWriteSequencesLock.withLock { writes -> (UInt64?, Bool) in
      guard let first = writes.first else { return (nil, false) }
      guard first.peripheral == origin, first.characteristic == characteristicOrigin else { return (nil, true) }
      return (writes.removeFirst().sequence, false)
    }
    guard let seq = sequence else {
      if rejectedOrigin {
        logger.warning("[BLE] ACK rejected origin \(ingressDiagnostics?.context ?? "stream=none") reason=originMismatch")
      } else {
        logger.debug("[BLE] didWriteValueFor with no recorded write, ignoring")
      }
      return
    }
    Task { await sm.handleDidWriteValue(peripheral, characteristic: characteristic, error: error, writeSequence: seq) }
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    _ = readyForWrite(from: peripheral)
  }

  func readyForWrite(from peripheral: CBPeripheral) -> Task<Void, Never>? {
    guard let sm = stateMachine else { return nil }
    let generation = generation(for: peripheral)
    return Task {
      if let generation {
        await sm.handlePeripheralReadyForWriteWithoutResponse(peripheral, generation: generation)
      }
      #if DEBUG
        await sm.noteWriteReadinessHandled(peripheral.identifier)
      #endif
    }
  }
}
