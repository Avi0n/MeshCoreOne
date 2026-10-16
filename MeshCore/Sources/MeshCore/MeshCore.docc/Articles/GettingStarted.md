# Getting Started with MeshCore

Connect to a MeshCore device and send your first message.

## Overview

This guide walks you through the essential steps to establish a connection with a MeshCore device and perform basic operations.

## Creating a Session

MeshCore communication requires two components: a transport layer and a session.

```swift
import MeshCore

// Create a WiFi transport pointed at the device
let transport = WiFiTransport()
await transport.setConnectionInfo(host: "192.168.1.100", port: 5000)

// Create a session with the transport
let session = MeshCoreSession(transport: transport)
```

For Bluetooth Low Energy, implement ``MeshTransport`` over your platform's BLE
stack (see <doc:CustomTransports>).

## Connecting to the Device

Call ``MeshCoreSession/start()`` to establish the connection:

```swift
do {
    try await session.start()
    print("Connected to device: \(session.currentSelfInfo?.name ?? "Unknown")")
} catch {
    print("Connection failed: \(error)")
}
```

After `start()` completes, the session is ready for use. The device's identity and configuration are available via ``MeshCoreSession/currentSelfInfo``.

## Sending a Message

Send a text message to a contact:

```swift
// Get the contact list
let contacts = try await session.getContacts()

// Send a message to the first contact
if let contact = contacts.first {
    let result = try await session.sendMessage(
        to: contact.publicKey,
        text: "Hello from Swift!"
    )
    print("Message queued, expected ACK: \(result.expectedAck.hexString)")
}
```

## Subscribing to Events

Listen for incoming messages and other events:

```swift
Task {
    for await event in await session.events() {
        switch event {
        case .contactMessageReceived(let message):
            print("Received: \(message.text)")
        case .acknowledgement(let code):
            print("ACK received: \(code.hexString)")
        case .advertisement(let publicKey):
            print("Saw node: \(publicKey.prefix(6).hexString)")
        default:
            break
        }
    }
}
```

## Disconnecting

When finished, stop the session:

```swift
await session.stop()
```

## Next Steps

- <doc:SessionLifecycle> - Learn about connection states and reconnection
- <doc:SendingMessages> - Explore message sending options and retries
- <doc:EventHandling> - Deep dive into event filtering and patterns
