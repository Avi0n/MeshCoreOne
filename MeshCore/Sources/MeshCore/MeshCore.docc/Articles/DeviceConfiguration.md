# Device Configuration

Configure radio settings, coordinates, channels, and other device parameters.

## Overview

MeshCore provides methods to configure all aspects of the connected device, from radio modulation to GPS coordinates.

## Device Name

Set the advertised name:

```swift
try await session.setName("MyNode")
```

## GPS Coordinates

Set the device's location:

```swift
try await session.setCoordinates(
    latitude: 37.7749,
    longitude: -122.4194
)
```

## Radio Settings

### Transmission Power

```swift
try await session.setTxPower(20)  // dBm
```

The maximum power depends on hardware. Check `selfInfo.maxTxPower`.

### Radio Parameters

Configure LoRa modulation:

```swift
try await session.setRadio(
    frequency: 915.0,        // MHz
    bandwidth: 125.0,        // kHz
    spreadingFactor: 10,     // 7-12
    codingRate: 5            // 5-8
)
```

Higher spreading factors increase range but reduce throughput.

### Fine Tuning

Adjust timing parameters:

```swift
try await session.setTuning(
    rxDelay: 1000,  // microseconds
    af: 0           // auto-frequency correction
)
```

## Device Time

### Get Current Time

```swift
let deviceTime = try await session.getTime()
```

### Set Time

Sync device clock to system time:

```swift
try await session.setTime(Date())
```

## Channel Configuration

### Get Channel Info

```swift
let channel = try await session.getChannel(index: 0)
print("Channel: \(channel.name)")
```

### Set Channel

```swift
try await session.setChannel(
    index: 0,
    name: "MyChannel",
    secret: .deriveFromName  // Or .custom(keyData)
)
```

Secret derivation options:
- `.deriveFromName`: Generates key from channel name
- `.custom(Data)`: Use explicit 16-byte key

## Flood Scope

Limit broadcast range:

```swift
// Scope to group
try await session.setFloodScope(.group(name: "TeamAlpha"))

// Clear scope (global flooding)
try await session.setFloodScope(.global)
```

## Other Parameters

### Granular Settings

Set individual parameters (preserves other settings):

```swift
try await session.setManualAddContacts(true)
try await session.setTelemetryModeBase(2)
try await session.setTelemetryModeLocation(1)
try await session.setMultiAcks(3)
try await session.setAdvertisementLocationPolicy(1)
```

### Bulk Settings

Set all parameters at once:

```swift
try await session.setOtherParams(
    manualAddContacts: true,
    telemetryModeEnvironment: 2,
    telemetryModeLocation: 1,
    telemetryModeBase: 2,
    advertisementLocationPolicy: 1,
    multiAcks: 3
)
```

## Custom Variables

Store key-value pairs on the device:

```swift
// Set variable
try await session.setCustomVar(key: "owner", value: "Alice")

// Get all variables
let vars = try await session.getCustomVars()
print(vars["owner"])  // "Alice"
```

## Device PIN

Set the BLE pairing PIN:

```swift
try await session.setDevicePin(123456)
```

## Reboot and Reset

### Reboot Device

```swift
try await session.reboot()
// Session will disconnect - create a new one after reboot
```

### Factory Reset

```swift
try await session.factoryReset()
// WARNING: Erases all data and settings
```
