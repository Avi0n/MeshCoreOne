# Event Handling

Subscribe to events, filter by type, and implement common patterns.

## Overview

``MeshEvent`` represents all possible notifications from a MeshCore device. The ``EventDispatcher`` manages subscriptions and delivers events via `AsyncStream`.

## Subscribing to Events

Get a stream of all events:

```swift
let eventStream = await session.events()

Task {
    for await event in eventStream {
        handleEvent(event)
    }
}
```

Each subscription is independent - multiple subscribers receive all events.

## Event Categories

### Connection Events
- `.connectionStateChanged(ConnectionState)` - Session state changes

### Command Responses
- `.ok(value:)` - Command succeeded
- `.error(code:)` - Command failed

### Device Information
- `.selfInfo(SelfInfo)` - Device identity after start
- `.deviceInfo(DeviceCapabilities)` - Hardware capabilities
- `.battery(BatteryInfo)` - Battery status
- `.currentTime(Date)` - Device clock

### Contact Events
- `.contactsStart(count:)` - Contact list transfer starting
- `.contact(MeshContact)` - Individual contact
- `.contactsEnd(lastModified:)` - Transfer complete
- `.newContact(MeshContact)` - New contact discovered

### Message Events
- `.messageSent(MessageSentInfo)` - Message queued
- `.contactMessageReceived(ContactMessage)` - Direct message received
- `.channelMessageReceived(ChannelMessage)` - Channel broadcast received
- `.messagesWaiting` - Messages available to fetch
- `.noMoreMessages` - Message queue empty

### Network Events
- `.advertisement(publicKey:)` - Node advertisement seen
- `.pathUpdate(publicKey:)` - Routing path changed
- `.acknowledgement(code:)` - Message delivery confirmed

### Binary Protocol
- `.statusResponse(StatusResponse)` - Remote node status
- `.telemetryResponse(TelemetryResponse)` - Sensor data
- `.mmaResponse(MMAResponse)` - Min/max/average data
- `.neighboursResponse(NeighboursResponse)` - Neighbor list

## Filtering Events

Use ``EventFilter`` for structured filtering:

```swift
let filter = EventFilter.acknowledgement(code: expectedAck)
let event = await session.waitForEvent(filter: filter, timeout: 10.0)
```

### Filter Types

```swift
// By event type
EventFilter.messageReceived
EventFilter.advertisement

// By acknowledgement code
EventFilter.acknowledgement(code: ackData)

// Custom predicate
EventFilter.custom { event in
    if case .contactMessageReceived(let msg) = event {
        return msg.text.contains("urgent")
    }
    return false
}
```

## Common Patterns

### Wait for Specific Event

```swift
let event = await session.waitForEvent(matching: { event in
    if case .loginSuccess = event { return true }
    return false
}, timeout: 30.0)
```

### Send and Wait

Avoid race conditions by subscribing before sending:

```swift
let result = try await session.sendAndWait(commandData) { event in
    if case .statusResponse(let response) = event {
        return response
    }
    return nil
}
```

### Multiple Event Types

```swift
for await event in await session.events() {
    switch event {
    case .contactMessageReceived(let msg):
        // Handle message
    case .acknowledgement(let code):
        // Handle ACK
    case .connectionStateChanged(.disconnected):
        // Handle disconnect
    default:
        continue
    }
}
```

## Event Attributes

Events expose filterable attributes:

```swift
let attrs = event.attributes
// ["publicKeyPrefix": Data, "textType": UInt8]
```

Use for advanced filtering without switch statements.

## Connection State Stream

Dedicated stream for connection state:

```swift
for await state in session.connectionState {
    updateConnectionUI(state)
}
```

This yields the current state immediately, then subsequent changes.
