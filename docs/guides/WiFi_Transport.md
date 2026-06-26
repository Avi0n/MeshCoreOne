# WiFi Transport Guide

This guide explains MeshCore One's WiFi/TCP transport path for connecting to MeshCore devices over the local network.

## Overview

MeshCore One supports two transport types:

- BLE (Bluetooth Low Energy) via `iOSBLETransport` in MC1Services
- WiFi/TCP via `WiFiTransport` in MeshCore

WiFi is configured manually (host + port) and is typically used for fixed installations or devices that expose a TCP service.

## Where the Code Lives

- MeshCore transport: `MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift`
- WiFi frame codec: `MeshCore/Sources/MeshCore/Transport/WiFiFrameCodec.swift`
- MeshCore One connection orchestration: `MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift` (WiFi-specific logic in `MC1Services/Sources/MC1Services/Connection/ConnectionManager+WiFi.swift`)
- UI for entering connection details: `MC1/Views/Onboarding/WiFiConnectionSheet.swift`
- UI for editing connection details while connected: `MC1/Views/Settings/Sections/WiFiEditSheet.swift`

## Transport Architecture

At a high level:

1. The app collects `host` and `port` from the user.
2. `ConnectionManager` creates a `WiFiTransport` (MeshCore) and configures it.
3. `ConnectionManager` creates a `MeshCoreSession` with that transport.
4. Services wire up and sync proceeds the same as BLE.

## Wire Protocol (WiFiFrameCodec)

WiFi transport uses a simple, length-prefixed framing over TCP:

- Outbound (app to device): `<` (0x3C) + 2-byte length (little-endian) + payload
- Inbound (device to app): `>` (0x3E) + 2-byte length (little-endian) + payload

The framed payload is the same MeshCore binary protocol payload used over BLE.

## Using WiFiTransport (MeshCore)

`WiFiTransport` is an actor that conforms to `MeshTransport`.

```swift
import MeshCore

let transport = WiFiTransport()
await transport.setConnectionInfo(host: "192.168.1.50", port: 5000)
try await transport.connect()

let session = MeshCoreSession(transport: transport)
try await session.start()
```

Notes:

- The transport itself does not implement discovery (mDNS/Bonjour) or keep-alives.
- Reconnect behavior is handled at higher layers (e.g., `ConnectionManager`).
- Use `Logger` for diagnostics; avoid `print()`.

## WiFi Reconnection & Health (ConnectionManager)

MeshCore One manages WiFi reconnection and connection health at the app layer:

- **Heartbeat probes:** When connected, the app sends a lightweight `getTime()` probe every 30 seconds to detect dead TCP connections (ESP32 stacks often ignore TCP keepalives).
- **Auto-reconnect:** If the probe fails, `ConnectionManager` tears down the session and starts a reconnect loop.
- **Exponential backoff:** Retry delay starts at 0.5s, doubles each attempt, and caps at 4s.
- **Max reconnect window:** Attempts stop after 30 seconds (`wifiMaxReconnectDuration`).
- **Cooldown:** A 35-second cooldown prevents rapid reattempts after a recent reconnect sequence.

## Troubleshooting

- Verify the iPhone and device are on the same reachable network.
- Double check the host and port (MeshCore One defaults to port 5000 in the WiFi connection UI).
- If you have a dev machine on the same network, `nc -zv <host> <port>` can help validate basic reachability.

## Further Reading

- [Architecture Overview](../Architecture.md)
- [BLE Transport Guide](BLE_Transport.md)
- [Development Guide](../Development.md)
- [User Guide](../User_Guide.md)
