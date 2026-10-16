# Protocol Internals

Understand the binary packet format and low-level protocol details.

## Overview

MeshCore uses a binary protocol for communication. Understanding the packet format is useful for debugging, extending functionality, or implementing compatible systems.

## Packet Format

All packets follow this structure:

```
[Command/Response Code (1 byte)] [Payload (N bytes)]
```

Multi-byte integers are **little-endian**.

## Command Codes

Commands are sent from the app to the device. See ``CommandCode`` for the complete list:

| Code | Name | Description |
|------|------|-------------|
| 0x01 | appStart | Initialize session |
| 0x02 | sendMessage | Send direct message |
| 0x03 | sendChannelMessage | Send channel broadcast |
| 0x04 | getContacts | Fetch contact list |
| 0x32 | binaryRequest | Binary protocol request |

## Response Codes

Responses come from the device. See ``ResponseCode``:

| Code | Name | Description |
|------|------|-------------|
| 0x00 | ok | Command succeeded |
| 0x01 | error | Command failed |
| 0x05 | selfInfo | Device identity |
| 0x07 | contactMessageReceived | Incoming message |
| 0x80+ | (Push) | Asynchronous notifications |

Push notifications (0x80+) arrive without a preceding command.

## Building Packets

``PacketBuilder`` constructs command packets:

```swift
// App start command
let packet = PacketBuilder.appStart(clientId: "MyApp")
// Result: [0x01, 0x03, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, "MyApp"...]

// Send message
let packet = PacketBuilder.sendMessage(
    to: destination,
    text: "Hello",
    timestamp: Date()
)
```

## Parsing Responses

``PacketParser`` converts raw data to events:

```swift
let event = PacketParser.parse(responseData)

switch event {
case .selfInfo(let info):
    // Handle device info
case .parseFailure(let data, let reason):
    // Handle parse error
default:
    break
}
```

## Binary Request Types

For complex data, use ``BinaryRequestType``:

| Type | Code | Description |
|------|------|-------------|
| status | 0x01 | Node status |
| keepAlive | 0x02 | Keep connection alive |
| telemetry | 0x03 | Sensor data |
| mma | 0x04 | Min/max/average |
| acl | 0x05 | Access control list |
| neighbours | 0x06 | Neighbor nodes |

## Example: appStart Packet

```
Offset  Bytes   Description
0       1       Command code (0x01)
1       1       Protocol version (0x03)
2       6       Reserved (spaces)
8       N       Client ID (UTF-8)
```

## Example: sendMessage Packet

```
Offset  Bytes   Description
0       1       Command code (0x02)
1       1       Message type (0x00 = text)
2       1       Retry attempt
3       4       Unix timestamp (LE uint32)
7       6       Destination pubkey prefix
13      N       Message text (UTF-8)
```

## Timestamps

Timestamps are Unix seconds as little-endian `UInt32`:

```swift
let timestamp = UInt32(date.timeIntervalSince1970)
data.append(contentsOf: withUnsafeBytes(of: timestamp.littleEndian) { Array($0) })
```

## Coordinates

GPS coordinates are scaled integers:

```swift
let lat = Int32(latitude * 1_000_000)  // 6 decimal places
let lon = Int32(longitude * 1_000_000)
```

## Public Keys

- Full key: 32 bytes
- Prefix: 6 bytes (used for routing)

Most commands accept the 6-byte prefix for efficiency.

## Statistics Categories

Request via ``StatsType``:

| Type | Code | Response Size |
|------|------|---------------|
| core | 0x00 | 9 bytes |
| radio | 0x01 | 12 bytes |
| packets | 0x02 | 24 bytes |
