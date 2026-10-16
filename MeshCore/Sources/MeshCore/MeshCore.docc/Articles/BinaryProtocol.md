# Binary Protocol

Request status, neighbors, and ACL data from remote nodes.

## Overview

The binary protocol provides efficient data transfer for complex requests like status queries, neighbor lists, and access control lists.

## Status Requests

Query a remote node's status:

```swift
let status = try await session.requestStatus(from: contact)

print("Battery: \(status.battery)mV")
print("Uptime: \(status.uptime)s")
print("TX Queue: \(status.txQueueLength)")
print("Noise Floor: \(status.noiseFloor)dBm")
print("Last RSSI: \(status.lastRSSI)dBm")
print("Packets Sent: \(status.packetsSent)")
print("Packets Received: \(status.packetsReceived)")
```

Using ``Destination``:

```swift
let status = try await session.requestStatus(from: .contact(contact))
```

## Neighbor Requests

Get the list of nodes a remote device can see:

```swift
let response = try await session.requestNeighbours(
    from: contact.publicKey,
    count: 255,      // Max neighbors to return
    offset: 0,       // Pagination offset
    orderBy: 0,      // Sort by RSSI
    pubkeyPrefixLength: 4
)

print("Total neighbors: \(response.totalCount)")
for neighbor in response.neighbours {
    print("  \(neighbor.publicKeyPrefix.hexString): SNR \(neighbor.snr), \(neighbor.secondsAgo)s ago")
}
```

### Fetch All Neighbors

Automatically paginate through all results:

```swift
let allNeighbors = try await session.fetchAllNeighbours(from: contact.publicKey)
```

## ACL Requests

Get the access control list:

```swift
let acl = try await session.requestACL(from: contact.publicKey)

for entry in acl.entries {
    print("\(entry.keyPrefix.hexString): permissions \(entry.permissions)")
}
```

## Local Statistics

Query the connected device directly:

### Core Stats

```swift
let stats = try await session.getStatsCore()
print("Battery: \(stats.batteryMV)mV")
print("Uptime: \(stats.uptimeSeconds)s")
print("Errors: \(stats.errors)")
print("Queue: \(stats.queueLength)")
```

### Radio Stats

```swift
let stats = try await session.getStatsRadio()
print("Noise Floor: \(stats.noiseFloor)dBm")
print("Last RSSI: \(stats.lastRSSI)dBm")
print("Last SNR: \(stats.lastSNR)dB")
print("TX Airtime: \(stats.txAirtimeSeconds)s")
print("RX Airtime: \(stats.rxAirtimeSeconds)s")
```

### Packet Stats

```swift
let stats = try await session.getStatsPackets()
print("Received: \(stats.received)")
print("Sent: \(stats.sent)")
print("Flood TX: \(stats.floodTx)")
print("Direct TX: \(stats.directTx)")
```

## Request Timeouts

Binary requests use `configuration.defaultTimeout`. Customize per-session:

```swift
let config = SessionConfiguration(
    defaultTimeout: 30.0  // seconds
)
let session = MeshCoreSession(transport: transport, configuration: config)
```

## Error Handling

```swift
do {
    let status = try await session.requestStatus(from: publicKey)
} catch MeshCoreError.timeout {
    print("Remote node didn't respond")
} catch MeshCoreError.invalidResponse(let expected, let got) {
    print("Unexpected response: expected \(expected), got \(got)")
}
```

When querying a room server, use ``requestStatus(from: MeshContact)`` or
``requestStatus(from:type:)`` so the session can select the room-server status layout.
