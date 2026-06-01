// BLEStateMachineProtocol.swift

@preconcurrency import CoreBluetooth
import Foundation

/// Protocol for BLE state machine operations used by ConnectionManager.
/// Enables dependency injection for testing.
public protocol BLEStateMachineProtocol: Actor {

    // MARK: - State Properties

    /// Whether the state machine is currently connected to a device
    var isConnected: Bool { get }

    /// Whether the state machine is currently handling iOS auto-reconnect or state restoration
    var isAutoReconnecting: Bool { get }

    /// UUID of the currently connected device, or nil if not connected
    var connectedDeviceID: UUID? { get }

    /// Current phase name for diagnostic logging
    var currentPhaseName: String { get }

    /// Current peripheral state for diagnostic logging (nil if no peripheral)
    var currentPeripheralState: String? { get }

    /// Current CBCentralManager state name for diagnostic logging
    var centralManagerStateName: String { get }

    /// Whether the Bluetooth central manager is in the powered-off state.
    var isBluetoothPoweredOff: Bool { get }

    // MARK: - Methods

    /// Checks if a device is connected to the system (possibly by another app).
    /// - Parameter deviceID: The UUID of the device to check
    /// - Returns: `true` if the device is connected to the system
    func isDeviceConnectedToSystem(_ deviceID: UUID) -> Bool

    /// Returns the UUIDs of all peripherals currently connected to the system via Nordic UART.
    /// Used for diagnostics — exposes the raw `retrieveConnectedPeripherals` result.
    func systemConnectedPeripheralIDs() -> [UUID]

    /// Starts a best-effort adoption of an already system-connected peripheral.
    ///
    /// This is used to recover from cases where iOS keeps the BLE link alive across app termination
    /// (e.g., app update) but state restoration did not run. The adoption uses the same discovery
    /// chain as state restoration/auto-reconnect.
    ///
    /// - Parameter deviceID: The UUID of the device to adopt.
    /// - Returns: `true` if an adoption attempt was started.
    func startAdoptingSystemConnectedPeripheral(_ deviceID: UUID) -> Bool

    /// Activates the central manager if needed.
    func activate()

    /// Sets a handler for auto-reconnecting events.
    /// Called when device disconnects but iOS is attempting automatic reconnection.
    /// - Parameters:
    ///   - deviceID: The disconnected device ID.
    ///   - errorInfo: Best-effort error summary from CoreBluetooth.
    func setAutoReconnectingHandler(_ handler: @escaping @Sendable (UUID, String) -> Void)

    /// Sets a handler called when Bluetooth powers on
    func setBluetoothPoweredOnHandler(_ handler: @escaping @Sendable () -> Void)

    /// Sets a handler for Bluetooth state changes
    func setBluetoothStateChangeHandler(_ handler: @escaping @Sendable (CBManagerState) -> Void)

    /// Sets the delay between write operations for pacing.
    func setWritePacingDelay(_ delay: TimeInterval)

    /// Sets a handler called when a device is discovered during scanning.
    func setDeviceDiscoveredHandler(_ handler: @escaping @Sendable (UUID, String?, Int) -> Void)

    /// Starts scanning for BLE peripherals. Works while connected.
    func startScanning()

    /// Stops an active BLE scan.
    func stopScanning()

    /// Gracefully shuts down the state machine, resuming all pending operations.
    /// Call before dropping the last reference to the actor.
    func shutdown()

    /// Notifies the state machine that the app entered background.
    /// Cancels foreground-only timeouts (auto-reconnect discovery) while
    /// preserving the RSSI keepalive for background connection maintenance.
    func appDidEnterBackground()

    /// Notifies the state machine that the app became active.
    /// Defensively restarts RSSI keepalive if connected, and re-arms
    /// auto-reconnect discovery timeout with generation fencing if in
    /// the auto-reconnecting phase.
    func appDidBecomeActive()
}
