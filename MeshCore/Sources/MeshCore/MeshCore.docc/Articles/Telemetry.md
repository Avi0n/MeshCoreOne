# Telemetry and Sensor Data

Decode sensor data using the Cayenne LPP format.

## Overview

MeshCore devices transmit sensor data using Cayenne Low Power Payload (LPP), a compact binary format designed for LoRa networks. The ``LPPDecoder`` parses this data into structured Swift types.

## Requesting Telemetry

### From Local Device

```swift
let response = try await session.getSelfTelemetry()
let dataPoints = response.dataPoints

for point in dataPoints {
    print("\(point.type.name): \(point.value)")
}
```

### From Remote Node

Using the binary protocol:

```swift
let response = try await session.requestTelemetry(from: contact.publicKey)
for point in response.dataPoints {
    print("Channel \(point.channel) \(point.type.name): \(point.value)")
}
```

## Decoding LPP Data

The ``LPPDecoder`` parses raw bytes:

```swift
let dataPoints = LPPDecoder.decode(rawData)
```

Each ``LPPDataPoint`` contains:
- `channel`: Sensor instance identifier (0-255)
- `type`: The ``LPPSensorType``
- `value`: Decoded ``LPPValue``

## Sensor Types

Common sensor types include:

| Type | Data Size | Resolution |
|------|-----------|------------|
| `.temperature` | 2 bytes | 0.1C |
| `.humidity` | 1 byte | 0.5% |
| `.barometer` | 2 bytes | 0.1 hPa |
| `.voltage` | 2 bytes | 0.01V |
| `.gps` | 9 bytes | 0.0001 degrees |
| `.accelerometer` | 6 bytes | 0.001G |

See ``LPPSensorType`` for the complete list.

## Working with Values

``LPPValue`` is an enum with typed cases:

```swift
for point in dataPoints {
    switch point.value {
    case .float(let value):
        print("\(point.type.name): \(value)")

    case .integer(let value):
        print("\(point.type.name): \(value)")

    case .gps(let lat, let lon, let alt):
        print("Location: \(lat), \(lon) @ \(alt)m")

    case .vector3(let x, let y, let z):
        print("Acceleration: x=\(x), y=\(y), z=\(z)")

    case .rgb(let r, let g, let b):
        print("Color: RGB(\(r), \(g), \(b))")

    case .timestamp(let date):
        print("Time: \(date)")

    case .digital(let on):
        print("State: \(on ? "ON" : "OFF")")
    }
}
```

## Min/Max/Average Data

Request aggregated statistics:

```swift
let response = try await session.requestMMA(
    from: contact.publicKey,
    start: Date().addingTimeInterval(-3600),  // Last hour
    end: Date()
)

for entry in response.data {
    print("Channel \(entry.channel) \(entry.type):")
    print("  Min: \(entry.min), Max: \(entry.max), Avg: \(entry.avg)")
}
```

## Encoding LPP Data

Create LPP payloads with ``LPPEncoder``:

```swift
var encoder = LPPEncoder()
encoder.addTemperature(channel: 0, celsius: 22.5)
encoder.addHumidity(channel: 1, percent: 65.0)
encoder.addGPS(channel: 2, latitude: 37.7749, longitude: -122.4194, altitude: 10.0)

let data = encoder.encode()
```
