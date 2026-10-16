# Custom Transports

Implement the MeshTransport protocol for new communication mediums.

## Overview

``MeshTransport`` defines the interface between ``MeshCoreSession`` and the physical communication layer. Implement this protocol to support transports beyond Bluetooth LE.

## Protocol Requirements

```swift
public protocol MeshTransport: Sendable {
    func connect() async throws
    func disconnect() async
    func send(_ data: Data) async throws
    var receivedData: AsyncStream<Data> { get async }
    var isConnected: Bool { get async }
}
```

## Implementing a Transport

Use an actor for thread safety:

```swift
actor SerialTransport: MeshTransport {
    private var continuation: AsyncStream<Data>.Continuation?
    private var _isConnected = false
    private let serialPort: SerialPort

    init(port: SerialPort) {
        self.serialPort = port
    }

    var isConnected: Bool { _isConnected }

    var receivedData: AsyncStream<Data> {
        AsyncStream { continuation in
            self.continuation = continuation
        }
    }

    func connect() async throws {
        try serialPort.open()
        _isConnected = true

        // Start reading in background
        Task {
            await readLoop()
        }
    }

    func disconnect() async {
        continuation?.finish()
        serialPort.close()
        _isConnected = false
    }

    func send(_ data: Data) async throws {
        guard _isConnected else {
            throw MeshTransportError.notConnected
        }
        try serialPort.write(data)
    }

    private func readLoop() async {
        while _isConnected {
            if let data = try? serialPort.read() {
                continuation?.yield(data)
            }
        }
    }
}
```

## Key Requirements

### Sendable Conformance

Transports must be `Sendable` for use with Swift concurrency. Actors automatically satisfy this.

### AsyncStream for Received Data

The `receivedData` property returns an `AsyncStream<Data>`. Store the continuation and yield data as it arrives:

```swift
continuation?.yield(incomingData)
```

Finish the stream on disconnect:

```swift
continuation?.finish()
```

### Error Handling

Throw appropriate errors:
- ``MeshTransportError/notConnected`` when sending while disconnected
- ``MeshTransportError/sendFailed(_:)`` for transmission errors
- ``MeshTransportError/connectionFailed(_:)`` for connection issues

## Using Custom Transports

```swift
let transport = SerialTransport(port: mySerialPort)
let session = MeshCoreSession(transport: transport)

try await session.start()
```

## Built-in Transports

MeshCore includes:
- ``WiFiTransport``: TCP transport for WiFi-bridged devices
- ``MockTransport``: In-memory transport for testing

Bluetooth Low Energy is implemented at the app layer, where pairing and
reconnection policy live: implement ``MeshTransport`` over your platform's
BLE stack as shown below.

## Testing with MockTransport

```swift
let transport = MockTransport()
let session = MeshCoreSession(transport: transport)

// Simulate incoming data
await transport.simulateReceive(responseData)

// Check sent data
let sentData = await transport.sentData
```
