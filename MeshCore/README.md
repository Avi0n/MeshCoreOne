# MeshCore

A Swift library for communicating with MeshCore devices over BLE. This is a Swift port of the [meshcore_py](https://github.com/meshcore-dev/meshcore_py) Python library.

## Requirements

- iOS 18.0+ / macOS 15.0+
- Swift 6.2+
- Xcode 26.0+

## Installation

Add MeshCore to your project using Swift Package Manager:

```swift
dependencies: [
    .package(path: "../MeshCore")
]
```

## Quick Start

```swift
import MeshCore

// Create a WiFi transport and session.
// For Bluetooth LE, implement MeshTransport over your platform's BLE stack.
let transport = WiFiTransport()
await transport.setConnectionInfo(host: "192.168.1.100", port: 5000)
let session = MeshCoreSession(transport: transport)

// Start the session
try await session.start()

// Query device info
let device = try await session.queryDevice()
print("Connected to \(device.model) running firmware \(device.firmwareBuild)")

// Get battery status
let battery = try await session.getBattery()
print("Battery: \(battery.level) mV")

// Send a message
let destination = Data(hexString: "abcdef123456")!
let result = try await session.sendMessage(to: destination, text: "Hello!")

// Stop the session
await session.stop()
```

## Features

### Device Management

```swift
// Query device capabilities
let device = try await session.queryDevice()

// Get/set device time
let time = try await session.getTime()
try await session.setTime(Date())

// Configure device
try await session.setName("MyDevice")
try await session.setCoordinates(latitude: 37.7749, longitude: -122.4194)
try await session.setTxPower(20)

// Get statistics
let coreStats = try await session.getStatsCore()
let radioStats = try await session.getStatsRadio()
let packetStats = try await session.getStatsPackets()
```

### Contact Management

```swift
// Get all contacts
let contacts = try await session.getContacts()

// Find contacts
let contact = await session.getContactByName("Alice")
let contact2 = await session.getContactByKeyPrefix("abcdef")

// Manage contacts
try await session.addContact(newContact)
try await session.removeContact(publicKey: contact.publicKey)
try await session.resetPath(publicKey: contact.publicKey)

// Export/import contacts
let uri = try await session.exportContact(publicKey: contact.publicKey)
try await session.importContact(cardData: cardData)
```

### Messaging

```swift
// Send direct message
let info = try await session.sendMessage(
    to: destination,
    text: "Hello!",
    timestamp: Date()
)

// Send with automatic retry
let result = try await session.sendMessageWithRetry(
    to: fullPublicKey,
    text: "Important message",
    maxAttempts: 3,
    floodAfter: 2
)

// Send channel message
try await session.sendChannelMessage(channel: 0, text: "Channel broadcast")

// Receive messages
let message = try await session.getMessage()
switch message {
case .contactMessage(let msg):
    print("From \(msg.senderPublicKeyPrefix.hexString): \(msg.text)")
case .channelMessage(let msg):
    print("Channel \(msg.channelIndex): \(msg.text)")
case .noMoreMessages:
    print("No messages waiting")
}

// Auto-fetch messages when available
await session.startAutoMessageFetching()
```

### Event Handling

```swift
// Subscribe to all events
for await event in await session.events() {
    switch event {
    case .contactMessageReceived(let msg):
        print("New message: \(msg.text)")
    case .advertisement(let publicKey):
        print("New node: \(publicKey.hexString)")
    case .acknowledgement(let code):
        print("ACK received: \(code.hexString)")
    default:
        break
    }
}

// Wait for specific event
let event = await session.waitForEvent(matching: { event in
    if case .acknowledgement = event { return true }
    return false
}, timeout: 5.0)

// Observe connection state
for await state in await session.connectionState {
    switch state {
    case .connected:
        print("Connected!")
    case .disconnected:
        print("Disconnected")
    case .reconnecting(let attempt):
        print("Reconnecting... attempt \(attempt)")
    case .failed(let error):
        print("Failed: \(error)")
    default:
        break
    }
}
```

### Binary Protocol (Remote Node Queries)

```swift
// Request status from remote node
let status = try await session.requestStatus(from: publicKey)
print("Remote battery: \(status.battery) mV, uptime: \(status.uptime)s")

// For room servers, use a typed request so the correct status layout is decoded.
let roomStatus = try await session.requestStatus(from: roomContact)

// Request telemetry
let telemetry = try await session.requestTelemetry(from: publicKey)

// Request MMA (Min/Max/Average) data
let mma = try await session.requestMMA(from: publicKey, start: startDate, end: endDate)

// Request neighbors list
let neighbors = try await session.requestNeighbours(from: publicKey)
let allNeighbors = try await session.fetchAllNeighbours(from: publicKey)
```

### Channel Management

```swift
// Get channel info
let channel = try await session.getChannel(index: 0)

// Set channel with derived secret
try await session.setChannel(index: 1, name: "General", secret: .deriveFromName)

// Set channel with explicit secret
try await session.setChannel(index: 2, name: "Private", secret: .explicit(secretData))
```

### Security

```swift
// Export/import private key
let privateKey = try await session.exportPrivateKey()
try await session.importPrivateKey(privateKey)

// Sign data
let signature = try await session.sign(dataToSign)

// Login to remote node
let loginResult = try await session.sendLogin(to: destination, password: "secret")
try await session.sendLogout(to: destination)
```

### Configuration

```swift
// Custom session configuration
let config = SessionConfiguration(
    clientIdentifier: "MyApp",
    defaultTimeout: 10.0
)
let session = MeshCoreSession(transport: transport, configuration: config)

// Set telemetry and other parameters
try await session.setOtherParams(
    manualAddContacts: false,
    telemetryModeEnvironment: 1,
    telemetryModeLocation: 1,
    telemetryModeBase: 1,
    advertisementLocationPolicy: 0,
    multiAcks: 1
)
```

## LPP Decoding

The library includes a Cayenne LPP decoder for telemetry data:

```swift
let dataPoints = LPPDecoder.decode(telemetryData)

for point in dataPoints {
    print("\(point.typeName) on channel \(point.channel): \(point.formattedValue)")
}

// Supported types: temperature, humidity, GPS, accelerometer, 
// barometer, voltage, illuminance, and 30+ more sensor types
```

## Testing

```bash
cd MeshCore
swift test
```

The library includes comprehensive unit tests for:
- Packet building and parsing
- Event dispatching
- LPP decoding
- Session integration flows

## Architecture

```
MeshCore/
├── Events/
│   ├── EventDispatcher.swift        # Async event broadcasting
│   ├── EventFilter.swift            # Event stream filtering
│   └── MeshEvent.swift              # All event types
├── LPP/
│   ├── LPPDecoder.swift             # Cayenne LPP parsing
│   └── LPPEncoder.swift             # Cayenne LPP encoding
├── Models/
│   ├── Contact.swift                # MeshContact struct
│   ├── Destination.swift            # Destination helpers
│   └── DeviceInfo.swift             # Device info structs
├── Protocol/
│   ├── ChannelCrypto.swift          # Channel message encryption
│   ├── DataExtensions.swift         # Data/byte helpers
│   ├── DirectMessageCrypto.swift    # Direct message encryption
│   ├── PacketBuilder.swift          # Command packet construction
│   ├── PacketCodes.swift            # Protocol constants
│   ├── PacketParser.swift           # Response packet parsing
│   ├── Parsers.swift                # Specialized response parsers
│   ├── RxLogParser.swift            # RF log packet parsing
│   └── RxLogTypes.swift             # RF log data types
├── Protocols/
│   └── MeshCoreSessionProtocol.swift # Session protocol for mocking
├── Session/
│   ├── ContactManager.swift         # Contact list management
│   ├── MeshCoreSession.swift        # Main session actor
│   ├── RequestContext.swift         # Command-response serializer
│   └── SessionConfiguration.swift   # Session configuration
└── Transport/
    ├── MeshTransport.swift          # Transport protocol
    ├── MockTransport.swift          # Testing mock
    ├── WiFiFrameCodec.swift         # WiFi frame encoding/decoding
    └── WiFiTransport.swift          # WiFi transport implementation
```

## Versioning & Distribution

MeshCore ships as a Swift Package consumed from source as a path dependency:

```swift
dependencies: [
    .package(path: "../MeshCore")
]
```

It is built alongside the consuming app rather than published as a standalone,
independently versioned package, so there are no library-specific release tags
to pin against.

## License

MIT License - see LICENSE file for details.
