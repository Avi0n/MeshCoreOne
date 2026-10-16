# Receiving Messages

Subscribe to events, fetch messages, and handle incoming data.

## Overview

MeshCore provides two ways to receive messages: subscribing to the event stream for real-time notifications, or polling the message queue.

## Event-Based Reception

The preferred approach is subscribing to ``MeshCoreSession/events()``:

```swift
Task {
    for await event in await session.events() {
        switch event {
        case .contactMessageReceived(let message):
            handleDirectMessage(message)
        case .channelMessageReceived(let message):
            handleChannelMessage(message)
        default:
            break
        }
    }
}
```

### Contact Messages

``ContactMessage`` contains:
- `senderPublicKeyPrefix`: First 6 bytes of sender's public key
- `text`: The message content
- `senderTimestamp`: When the sender sent it
- `pathLength`: Number of hops the message travelled
- `signature`: Optional cryptographic signature
- `snr`: Signal-to-noise ratio (if available)

```swift
func handleDirectMessage(_ message: ContactMessage) {
    let sender = message.senderPublicKeyPrefix.hexString
    print("[\(sender)] \(message.text)")

    if let sig = message.signature {
        print("  Signed message (\(sig.count) bytes)")
    }
}
```

### Channel Messages

``ChannelMessage`` contains:
- `channelIndex`: Which channel received the message
- `text`: The message content
- `senderTimestamp`: When it was sent
- `pathLength`: Hop count
- `snr`: Signal quality

```swift
func handleChannelMessage(_ message: ChannelMessage) {
    print("[Channel \(message.channelIndex)] \(message.text)")
}
```

## Polling with getMessage()

Alternatively, poll the device's message queue:

```swift
while true {
    let result = try await session.getMessage()

    switch result {
    case .contactMessage(let msg):
        handleDirectMessage(msg)
    case .channelMessage(let msg):
        handleChannelMessage(msg)
    case .noMoreMessages:
        break  // Queue empty
    }

    if case .noMoreMessages = result { break }
}
```

## Auto-Fetching Messages

Enable automatic message fetching:

```swift
// Start auto-fetching
await session.startAutoMessageFetching()

// Messages are automatically fetched and dispatched via events()
// when the device signals messagesWaiting

// Stop when done
session.stopAutoMessageFetching()
```

This listens for `.messagesWaiting` events and automatically calls `getMessage()`.

## messagesWaiting Event

The device signals when messages are queued:

```swift
for await event in await session.events() {
    if case .messagesWaiting = event {
        // Manually fetch if not using auto-fetch
        _ = try await session.getMessage()
    }
}
```

## Finding the Sender

Match the sender prefix to a known contact:

```swift
func handleDirectMessage(_ message: ContactMessage) {
    let sender = session.getContactByKeyPrefix(message.senderPublicKeyPrefix)
    let name = sender?.advertisedName ?? message.senderPublicKeyPrefix.hexString
    print("From \(name): \(message.text)")
}
```
