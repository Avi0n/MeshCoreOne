@preconcurrency import CoreBluetooth
import Foundation
import MeshCore
import os

/// iOS BLE transport using the BLEStateMachine.
///
/// This is a thin wrapper around `BLEStateMachine` that conforms to `MeshTransport`.
/// The state machine handles all CoreBluetooth complexity including:
///
/// - **State restoration**: Background app relaunch via CoreBluetooth restoration
/// - **Auto-reconnect**: iOS 17+ automatic reconnection handling
/// - **Proper cleanup**: Continuation and resource management on all transitions
/// - **Device switching**: Switch between devices without full disconnect/reconnect
///
/// ## Usage
///
/// ```swift
/// // Basic usage with shared state machine
/// let stateMachine = BLEStateMachine()
/// let transport = iOSBLETransport(stateMachine: stateMachine)
/// await transport.setDeviceID(deviceUUID)
/// try await transport.connect()
///
/// // Switch to a different device
/// try await transport.switchDevice(to: otherDeviceUUID)
/// ```
actor iOSBLETransport: MeshTransport {

    private let logger = PersistentLogger(subsystem: "com.mc1", category: "iOSBLETransport")

    private let stateMachine: BLEStateMachine
    private var deviceID: UUID?

    /// Lock-protected data stream storage.
    /// Using a lock allows synchronous access from BLEStateMachine callbacks without spawning Tasks.
    private let dataStreamLock = OSAllocatedUnfairLock<AsyncStream<Data>?>(initialState: nil)

    // MARK: - Initialization

    /// Creates an iOS BLE transport with an optional shared state machine.
    ///
    /// - Parameter stateMachine: The BLE state machine to use. If nil, creates a new one.
    init(stateMachine: BLEStateMachine? = nil) {
        self.stateMachine = stateMachine ?? BLEStateMachine()
    }

    // MARK: - Configuration

    /// Sets the device UUID to connect to.
    ///
    /// - Parameter id: The UUID of the BLE device.
    func setDeviceID(_ id: UUID) {
        deviceID = id
    }

    /// Sets a handler for disconnection events.
    ///
    /// - Parameter handler: Called when the device disconnects, with the device ID and optional error.
    func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) async {
        await stateMachine.setDisconnectionHandler(handler)
    }

    /// Sets a handler for reconnection events.
    ///
    /// When iOS auto-reconnect completes, the transport captures the data stream
    /// and then calls your handler. The `receivedData` property will be ready
    /// when your handler is called.
    ///
    /// - Parameter handler: Called when iOS auto-reconnect completes successfully.
    func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) async {
        await stateMachine.setReconnectionHandler { [dataStreamLock, logger] deviceID, stream in
            // Capture the stream synchronously using lock (no Task spawning).
            // This ensures the stream is available before any handler code runs.
            logger.info("[BLE] Auto-reconnect stream captured for device: \(deviceID.uuidString.prefix(8))")
            dataStreamLock.withLock { $0 = stream }
            handler(deviceID)
        }
    }

    // MARK: - MeshTransport Protocol

    /// Whether the transport is currently connected to a device.
    var isConnected: Bool {
        get async { await stateMachine.isConnected }
    }

    /// Async stream of data received from the connected device.
    ///
    /// Returns an empty stream if not connected.
    var receivedData: AsyncStream<Data> {
        dataStreamLock.withLock { $0 } ?? AsyncStream { $0.finish() }
    }

    /// Connects to the configured device.
    ///
    /// This method is idempotent: if already connected to the same device,
    /// it returns without error. Use ``switchDevice(to:)`` to change devices.
    ///
    /// - Throws: `BLEError.deviceNotFound` if no device ID is set.
    /// - Throws: `BLEError.connectionFailed` if connected to a different device.
    /// - Throws: `BLEError` for connection failures.
    func connect() async throws {
        let connectedID = await stateMachine.connectedDeviceID
        let effectiveDeviceID = self.deviceID ?? connectedID

        guard let deviceID = effectiveDeviceID else {
            logger.warning("[BLE] connect() called with no device ID set")
            throw BLEError.deviceNotFound
        }

        // Already connected - check if it's the same device
        if await stateMachine.isConnected {
            if connectedID == deviceID {
                logger.info("Already connected to device: \(deviceID)")
                return
            } else {
                throw BLEError.connectionFailed("Already connected to different device: \(connectedID?.uuidString ?? "unknown"). Use switchDevice() instead.")
            }
        }

        logger.info("Connecting to device: \(deviceID)")
        let stream = try await stateMachine.connect(to: deviceID)
        logger.info("[BLE] Stream captured for device: \(deviceID.uuidString.prefix(8))")
        dataStreamLock.withLock { $0 = stream }
    }

    /// Disconnects from the current device.
    func disconnect() async {
        logger.info("Disconnecting")
        await stateMachine.disconnect()
        dataStreamLock.withLock { $0 = nil }
    }

    /// Sends data to the connected device.
    ///
    /// - Parameter data: The data to send.
    /// - Throws: `BLEError.notConnected` if not connected.
    /// - Throws: `BLEError.writeError` if the write fails.
    func send(_ data: Data) async throws {
        try await stateMachine.send(data)
    }

    /// Whether the connected radio's write characteristic advertises Write-Without-Response.
    var supportsWriteWithoutResponse: Bool {
        get async { await stateMachine.supportsWriteWithoutResponse }
    }

    /// Sends data as an unacknowledged ATT Write Command, enabling request pipelining.
    ///
    /// - Parameter data: The data to send.
    /// - Throws: `BLEError.notConnected` if not connected.
    func sendWithoutResponse(_ data: Data) async throws {
        try await stateMachine.sendWithoutResponse(data)
    }

    // MARK: - Extended API

    /// UUID of the currently connected device, or nil if not connected.
    var connectedDeviceID: UUID? {
        get async { await stateMachine.connectedDeviceID }
    }

    /// Switches to a different device.
    ///
    /// Disconnects from the current device (if any) and connects to the new one.
    /// More efficient than separate disconnect/connect calls.
    ///
    /// - Parameter deviceID: UUID of the new device to connect to.
    /// - Throws: `BLEError` if connection fails.
    func switchDevice(to deviceID: UUID) async throws {
        logger.info("Switching to device: \(deviceID)")
        self.deviceID = deviceID
        let stream = try await stateMachine.switchDevice(to: deviceID)
        dataStreamLock.withLock { $0 = stream }
    }
}
