# Sending Messages

Send direct messages, channel broadcasts, and commands through the mesh network.

## Overview

MeshCore supports several message types: direct messages to contacts, broadcast messages to channels, and command messages for remote device control.

## Direct Messages

Send a text message to a specific contact:

```swift
let result = try await session.sendMessage(
    to: contact.publicKey,
    text: "Hello!"
)
```

The method returns ``MessageSentInfo`` containing:
- `expectedAck`: The acknowledgement code to match
- `suggestedTimeoutMs`: Recommended wait time for ACK

### Using Destination

The ``Destination`` type provides flexible addressing:

```swift
// From a contact
let result = try await session.sendMessage(
    to: .contact(myContact),
    text: "Hello!"
)

// From raw public key data
let result = try await session.sendMessage(
    to: .data(publicKeyData),
    text: "Hello!"
)

// From hex string
let result = try await session.sendMessage(
    to: .hex("a1b2c3d4e5f6..."),
    text: "Hello!"
)
```

## Waiting for Acknowledgement

Messages are acknowledged by the recipient. Match the ACK:

```swift
let result = try await session.sendMessage(to: destination, text: "Hello!")

let ack = await session.waitForEvent(matching: { event in
    if case .acknowledgement(let code) = event {
        return code == result.expectedAck
    }
    return false
}, timeout: Double(result.suggestedTimeoutMs) / 1000.0)

if ack != nil {
    print("Message delivered!")
} else {
    print("No acknowledgement received")
}
```

## Retry with Path Reset

For unreliable connections, use ``MeshCoreSession/sendMessageWithRetry(to:text:timestamp:maxAttempts:floodAfter:maxFloodAttempts:timeout:)``:

```swift
let result = try await session.sendMessageWithRetry(
    to: contact.publicKey,  // Must be full 32-byte key
    text: "Important message",
    maxAttempts: 3,
    floodAfter: 2  // Reset to flood routing after 2 failures
)

if result != nil {
    print("Message acknowledged")
} else {
    print("Delivery failed after all attempts")
}
```

This method:
1. Sends the message and waits for ACK
2. Retries up to `maxAttempts` times
3. After `floodAfter` failures, resets the routing path to flood mode
4. Returns `nil` if all attempts fail

## Channel Messages

Broadcast to all nodes on a channel:

```swift
try await session.sendChannelMessage(
    channel: 0,
    text: "Broadcast to everyone!"
)
```

Channel messages don't receive acknowledgements.

## Command Messages

Send a command to a remote node:

```swift
let result = try await session.sendCommand(
    to: contact.publicKey.prefix(6),
    command: "status"
)
```

Commands trigger actions on the remote device and may return responses via the event stream.

## Login/Logout

Authenticate with password-protected nodes:

```swift
// Login
let result = try await session.sendLogin(
    to: .contact(contact),
    password: "secret123"
)

// Wait for login result
let loginEvent = await session.waitForEvent(matching: { event in
    if case .loginSuccess = event { return true }
    if case .loginFailed = event { return true }
    return false
})

// Logout when done
try await session.sendLogout(to: contact.publicKey)
```
