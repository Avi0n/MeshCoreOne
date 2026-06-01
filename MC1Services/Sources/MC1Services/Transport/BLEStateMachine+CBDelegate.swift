// BLEStateMachine+CBDelegate.swift
@preconcurrency import CoreBluetooth
import Foundation
import os

// MARK: - Delegate Handler

/// Bridges CoreBluetooth delegate callbacks to the actor.
///
/// This class is necessary because actors cannot directly conform to
/// Objective-C delegate protocols. All callbacks dispatch to the actor.
///
/// ## Callback ordering (C11)
/// Control callbacks (didConnect, didDiscoverServices, etc.) are forwarded via
/// unstructured `Task {}`, which does not guarantee FIFO ordering on the actor.
/// This is safe because each handler validates the expected phase before proceeding.
/// An out-of-order callback (e.g., didDiscoverServices arriving before didConnect
/// has been processed) will fail the phase guard and be ignored. The timeout
/// mechanism will then retry the operation.
///
/// For data reception (`didUpdateValueFor`), data is yielded directly to an AsyncStream
/// continuation rather than spawning Tasks. This preserves the ordering guaranteed by
/// the serial CBCentralManager queue, avoiding the race conditions that occur when
/// multiple unstructured Tasks compete for actor access with priority-based scheduling.
final class BLEDelegateHandler: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {

    weak var stateMachine: BLEStateMachine?

    private let logger = PersistentLogger(subsystem: "com.mc1", category: "BLEDelegateHandler")

    /// Lock-protected continuation for yielding received data directly.
    /// Using OSAllocatedUnfairLock ensures thread-safe access from the CBCentralManager queue.
    private let dataContinuationLock = OSAllocatedUnfairLock<AsyncStream<Data>.Continuation?>(initialState: nil)

    /// Write sequence number for correlating didWriteValue callbacks with the active write.
    /// Set by the actor before calling writeValue, read by the delegate to tag the callback.
    let writeSequenceLock = OSAllocatedUnfairLock<UInt64>(initialState: 0)

    /// Sets the data continuation for direct yielding from delegate callbacks.
    /// Call this when transitioning to connected state.
    func setDataContinuation(_ continuation: AsyncStream<Data>.Continuation?) {
        dataContinuationLock.withLock { $0 = continuation }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard let sm = stateMachine else { return }
        Task { await sm.handleCentralManagerDidUpdateState(central.state) }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        guard let sm = stateMachine else { return }
        // Extract peripheral synchronously before crossing actor boundary
        guard let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
              let peripheral = peripherals.first else {
            return
        }
        Task { await sm.handleWillRestoreState(peripheral) }
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
        _ = dataContinuationLock.withLock { $0?.yield(data) }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard let sm = stateMachine else { return }
        Task { await sm.handleDidReadRSSI(RSSI: RSSI, error: error) }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let sm = stateMachine else { return }
        // C8: Capture the write sequence at callback time (on the CB queue) to correlate
        // this callback with the write that triggered it.
        let seq = writeSequenceLock.withLock { $0 }
        Task { await sm.handleDidWriteValue(peripheral, characteristic: characteristic, error: error, writeSequence: seq) }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard let sm = stateMachine else { return }
        Task { await sm.handlePeripheralReadyForWriteWithoutResponse() }
    }
}
