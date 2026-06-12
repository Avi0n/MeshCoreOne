// BLEPhase.swift
@preconcurrency import CoreBluetooth
import Foundation

/// Represents the complete BLE connection lifecycle as explicit states.
/// Each state owns exactly the resources it needs.
///
/// This enum is marked @unchecked Sendable because it contains non-Sendable
/// CoreBluetooth types (CBPeripheral, CBService, CBCharacteristic). The
/// BLEStateMachine actor ensures these are only accessed from appropriate contexts.
enum BLEPhase: @unchecked Sendable {

    /// Initial state, no operations in progress
    case idle

    /// Waiting for CBCentralManager to reach .poweredOn
    case waitingForBluetooth(
        continuation: CheckedContinuation<Void, Error>
    )

    /// Actively connecting to a peripheral
    case connecting(
        peripheral: CBPeripheral,
        continuation: CheckedContinuation<Void, Error>,
        timeoutTask: Task<Void, Never>
    )

    /// Connected, discovering services
    case discoveringServices(
        peripheral: CBPeripheral,
        continuation: CheckedContinuation<Void, Error>
    )

    /// Services found, discovering characteristics
    case discoveringCharacteristics(
        peripheral: CBPeripheral,
        service: CBService,
        continuation: CheckedContinuation<Void, Error>
    )

    /// Characteristics found, subscribing to notifications
    case subscribingToNotifications(
        peripheral: CBPeripheral,
        tx: CBCharacteristic,
        rx: CBCharacteristic,
        continuation: CheckedContinuation<Void, Error>
    )

    /// Discovery chain complete, continuation consumed.
    /// Transitional phase between notification subscription success and
    /// `connect()` creating the data stream. Holds characteristics without
    /// a continuation, preventing double-resume if `cancelCurrentOperation`
    /// runs before `connect()` transitions to `.connected`.
    case discoveryComplete(
        peripheral: CBPeripheral,
        tx: CBCharacteristic,
        rx: CBCharacteristic
    )

    /// Fully connected and ready for communication
    case connected(
        peripheral: CBPeripheral,
        tx: CBCharacteristic,
        rx: CBCharacteristic,
        dataContinuation: AsyncStream<Data>.Continuation
    )

    /// iOS auto-reconnect in progress.
    /// Progressively populated as discovery completes.
    case autoReconnecting(
        peripheral: CBPeripheral,
        tx: CBCharacteristic?,
        rx: CBCharacteristic?
    )

    /// iOS state restoration received, waiting for Bluetooth power state.
    /// Transitions to .autoReconnecting when Bluetooth powers on.
    case restoringState(peripheral: CBPeripheral)

    /// Intentionally disconnecting
    case disconnecting(
        peripheral: CBPeripheral
    )

    // MARK: - Computed Properties

    /// Human-readable name for logging
    var name: String {
        switch self {
        case .idle: "idle"
        case .waitingForBluetooth: "waitingForBluetooth"
        case .connecting: "connecting"
        case .discoveringServices: "discoveringServices"
        case .discoveringCharacteristics: "discoveringCharacteristics"
        case .subscribingToNotifications: "subscribingToNotifications"
        case .discoveryComplete: "discoveryComplete"
        case .connected: "connected"
        case .autoReconnecting: "autoReconnecting"
        case .restoringState: "restoringState"
        case .disconnecting: "disconnecting"
        }
    }

    /// Whether this phase is part of the service/characteristic discovery chain.
    /// Used by `cleanupPhaseResources` to preserve the discovery timeout when
    /// transitioning within the chain.
    var isDiscoveryChain: Bool {
        switch self {
        case .discoveringServices, .discoveringCharacteristics, .subscribingToNotifications:
            return true
        default:
            return false
        }
    }

    /// Whether this phase represents an active operation (not idle)
    var isActive: Bool {
        if case .idle = self { return false }
        return true
    }

    /// The peripheral associated with this phase, if any
    var peripheral: CBPeripheral? {
        switch self {
        case .connecting(let p, _, _),
             .discoveringServices(let p, _),
             .discoveringCharacteristics(let p, _, _),
             .subscribingToNotifications(let p, _, _, _),
             .discoveryComplete(let p, _, _),
             .connected(let p, _, _, _),
             .autoReconnecting(let p, _, _),
             .restoringState(let p),
             .disconnecting(let p):
            return p
        case .idle, .waitingForBluetooth:
            return nil
        }
    }

    /// The device ID associated with this phase, if any
    var deviceID: UUID? {
        peripheral?.identifier
    }

}
