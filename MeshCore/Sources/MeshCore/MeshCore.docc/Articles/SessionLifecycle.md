# Session Lifecycle

Manage connection states, handle disconnections, and implement reconnection logic.

## Overview

A ``MeshCoreSession`` progresses through several states during its lifecycle. Understanding these states helps you build robust applications that handle connection issues gracefully.

## Connection States

The session exposes its state via ``MeshCoreSession/connectionState``:

```swift
for await state in session.connectionState {
    switch state {
    case .disconnected:
        showDisconnectedUI()
    case .connecting:
        showConnectingIndicator()
    case .connected:
        showConnectedUI()
    case .reconnecting(let attempt):
        showReconnecting(attempt: attempt)
    case .failed(let error):
        handleConnectionError(error)
    }
}
```

## Starting a Session

The ``MeshCoreSession/start()`` method:

1. Connects via the transport layer
2. Sends the `appStart` command
3. Receives device self-info
4. Starts the background receive loop

```swift
do {
    try await session.start()
    // Session is now ready
} catch MeshTransportError.connectionFailed(let reason) {
    print("Transport error: \(reason)")
} catch MeshCoreError.timeout {
    print("Device didn't respond to appStart")
}
```

## Stopping a Session

Call ``MeshCoreSession/stop()`` to cleanly disconnect:

```swift
await session.stop()
```

This method:
- Cancels all pending operations
- Stops the receive loop
- Closes the transport connection
- Updates state to `.disconnected`

After stopping, the session cannot be reused. Create a new session to reconnect.

## Handling Disconnections

When the transport disconnects unexpectedly, the session:
- Dispatches a `.connectionStateChanged(.disconnected)` event
- Terminates the `events()` stream
- Sets `isRunning` to `false`

Monitor for disconnection:

```swift
Task {
    for await event in await session.events() {
        if case .connectionStateChanged(.disconnected) = event {
            // Handle unexpected disconnection
            await attemptReconnection()
        }
    }
}
```

## Implementing Reconnection

MeshCore doesn't provide automatic reconnection. Implement your own logic:

```swift
func attemptReconnection() async {
    var attempts = 0
    let maxAttempts = 5

    while attempts < maxAttempts {
        attempts += 1

        do {
            // Create a new session
            let newTransport = WiFiTransport()
            await newTransport.setConnectionInfo(host: host, port: port)
            let newSession = MeshCoreSession(transport: newTransport)
            try await newSession.start()

            // Success - update your app state
            self.session = newSession
            return
        } catch {
            let delay = Double(attempts) * 2.0  // Exponential backoff
            try? await Task.sleep(for: .seconds(delay))
        }
    }

    // Max attempts reached
    showReconnectionFailed()
}
```

## Best Practices

- **Always handle errors** from `start()` - transport and protocol errors are common
- **Create fresh sessions** after disconnection rather than reusing
- **Monitor the event stream** for unexpected disconnections
- **Implement exponential backoff** in reconnection logic
- **Clean up resources** by calling `stop()` even if disconnected
