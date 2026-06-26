# MeshCore API Reference

The `MeshCore` framework provides the low-level protocol implementation for MeshCore mesh networking devices.

**Note:** This document covers the primary public APIs. The full API surface includes 80+ additional methods for advanced use cases like signing, custom variables, telemetry, and device configuration.

## Package Information

- **Location:** `MeshCore/`
- **Type:** Swift Package (MeshCore library plus a MeshCoreTestSupport library target)
- **Dependencies:** No runtime dependencies (a swift-docc-plugin build/documentation dependency only)

---

## MeshCoreSession (public, actor)

**File:** `MeshCore/Sources/MeshCore/Session/MeshCoreSession.swift`

The primary entry point for communicating with a MeshCore device. Serializes all device communication through actor isolation.

### Lifecycle

| Method | Description |
|--------|-------------|
| `init(transport:configuration:clock:)` | Initializes a session with the given transport, optional configuration (defaults to `.default`), and optional clock for testing |
| `start() async throws` | Connects to the device and initializes the session |
| `stop() async` | Stops the session and disconnects the transport |

### Core Properties

| Property | Type | Description |
|----------|------|-------------|
| `currentSelfInfo` | `SelfInfo?` | Returns the device's self info after session start, populated after `start()` completes |
| `cachedContacts` | `[MeshContact]` | Returns currently cached contacts without making a device request |
| `cachedPendingContacts` | `[MeshContact]` | Returns pending contacts awaiting confirmation |
| `deviceTime` | `Date?` | Returns the last known device time, or `nil` if not yet queried |
| `isContactsDirty` | `Bool` | Indicates whether the contact cache needs refreshing |
| `connectionState` | `AsyncStream<ConnectionState>` | Stream reflecting the current connection state |

### Events

| Method | Description |
|--------|-------------|
| `events() async -> AsyncStream<MeshEvent>` | Returns a stream of all incoming events from the device |
| `waitForEvent(matching:timeout:) async -> MeshEvent?` | Waits for a specific event type with optional filtering and timeout |
| `waitForEvent(filter:timeout:) async -> MeshEvent?` | Waits for an event matching an `EventFilter` with timeout |
| `sendAndWait(_:matching:timeout:) async throws -> T` | Sends a command and waits for a matching response, avoiding race conditions |

### Messaging

| Method | Description |
|--------|-------------|
| `sendMessage(to:text:timestamp:) async throws -> MessageSentInfo` | Sends a direct message to a contact (6-byte public key prefix or `Destination`) |
| `sendMessageWithRetry(to:text:timestamp:maxAttempts:floodAfter:maxFloodAttempts:timeout:) async throws -> MessageSentInfo?` | Sends with automatic retry and flood fallback (requires 32-byte key) |
| `sendChannelMessage(channel:text:timestamp:) async throws` | Broadcasts to a channel slot (0-15) |
| `sendCommand(to:command:timestamp:) async throws -> MessageSentInfo` | Sends a command message to a remote node |
| `sendAdvertisement(flood:) async throws` | Sends an advertisement broadcast, optionally using flood routing |
| `sendLogin(to:password:) async throws -> MessageSentInfo` | Sends a login request to a remote node (accepts `Data` or `Destination`) |
| `sendLogout(to:) async throws` | Sends a logout request to a remote node |
| `sendKeepAlive(to:syncSince:) async throws -> MessageSentInfo` | Sends a keep-alive to a room server with sync watermark |
| `sendStatusRequest(to:) async throws -> MessageSentInfo` | Requests status information from a remote node |
| `sendTelemetryRequest(to:) async throws -> MessageSentInfo` | Requests telemetry data from a remote node |
| `sendPathDiscovery(to:) async throws -> MessageSentInfo` | Initiates path discovery to a remote node |
| `sendTrace(tag:authCode:flags:path:) async throws -> MessageSentInfo` | Sends a trace packet through the mesh network for debugging |
| `getMessage(timeout:) async throws -> MessageResult` | Fetches next pending message from device queue |
| `startAutoMessageFetching() async` | Begins automatically fetching messages on notifications |
| `stopAutoMessageFetching()` | Stops automatic message fetching |

### Contact Management

| Method | Description |
|--------|-------------|
| `public func getContacts(since lastModified: Date? = nil) async throws -> [MeshContact]` | Fetches contacts from device, optionally filtering to contacts modified since a date |
| `getContact(publicKey:) async throws -> MeshContact?` | Fetches a single contact by full public key |
| `ensureContacts(force:) async throws -> [MeshContact]` | Ensures contacts are loaded, fetching from device if needed or if cache is dirty |
| `getContactByName(_:exactMatch:) -> MeshContact?` | Finds a contact by advertised name with optional exact matching |
| `getContactByKeyPrefix(_:) -> MeshContact?` | Finds a contact by public key prefix (accepts `String` hex or `Data`) |
| `popPendingContact(publicKey:) -> MeshContact?` | Removes and returns a pending contact by hex string public key |
| `flushPendingContacts()` | Removes all pending contacts from the cache |
| `setAutoUpdateContacts(_:)` | Enables or disables automatic contact updates on advertisements |
| `addContact(_:) async throws` | Adds a contact to the device's contact list |
| `updateContact(publicKey:type:flags:outPathLength:outPath:advertisedName:lastAdvertisement:latitude:longitude:) async throws` | Low-level method to update or create a contact with full details |
| `changeContactPath(_:path:) async throws` | Changes the routing path for a contact while preserving other info |
| `changeContactFlags(_:flags:) async throws` | Changes the flags for a contact while preserving other info |
| `removeContact(publicKey:) async throws` | Removes a contact from the device's contact list |
| `resetPath(publicKey:) async throws` | Resets routing path for a contact, forcing route rediscovery |
| `shareContact(publicKey:) async throws` | Shares a contact with nearby devices via broadcast |
| `exportContact(publicKey:) async throws -> String` | Exports a contact as a shareable URI string (nil for self) |
| `importContact(cardData:) async throws` | Imports a contact from encoded contact card data |

### Device Configuration

| Method | Description |
|--------|-------------|
| `sendAppStart() async throws -> SelfInfo` | Sends the app-start command to initialize communication (usually called by `start()`) |
| `queryDevice() async throws -> DeviceCapabilities` | Queries hardware capabilities and firmware |
| `getBattery() async throws -> BatteryInfo` | Requests battery level and voltage |
| `getTime() async throws -> Date` | Gets the current device time |
| `setTime(_:) async throws` | Sets the device's current time |
| `setName(_:) async throws` | Sets the device's advertised name (max 32 bytes UTF-8) |
| `setCoordinates(latitude:longitude:) async throws` | Sets device GPS coordinates for advertisements |
| `setTxPower(_:) async throws` | Sets radio transmission power level in dBm |
| `setRadio(frequency:bandwidth:spreadingFactor:codingRate:) async throws` | Configures LoRa radio parameters |
| `setTuning(rxDelay:af:) async throws` | Configures radio timing parameters for fine-tuning |
| `setOtherParams(manualAddContacts:telemetryModeEnvironment:telemetryModeLocation:telemetryModeBase:advertisementLocationPolicy:multiAcks:) async throws` | Sets miscellaneous device parameters (low-level, use granular setters instead) |
| `setTelemetryModeBase(_:) async throws` | Sets base telemetry mode (0-3), preserving other settings |
| `setTelemetryModeLocation(_:) async throws` | Sets location telemetry mode (0-3), preserving other settings |
| `setTelemetryModeEnvironment(_:) async throws` | Sets environment telemetry mode (0-3), preserving other settings |
| `setManualAddContacts(_:) async throws` | Sets manual contact approval mode, preserving other settings |
| `setMultiAcks(_:) async throws` | Sets multi-acks count, preserving other settings |
| `setAdvertisementLocationPolicy(_:) async throws` | Sets advertisement location policy, preserving other settings |
| `getAutoAddConfig() async throws -> AutoAddConfig` | Returns auto-add config (bitmask plus max-hops) (v1.12+) |
| `setAutoAddConfig(_:) async throws` | Sets auto-add config (v1.12+) |
| `setDevicePin(_:) async throws` | Sets the device PIN for administrative access (4-digit as UInt32) |
| `reboot() async throws` | Reboots the device (session will be disconnected) |

### Telemetry and Statistics

| Method | Description |
|--------|-------------|
| `getSelfTelemetry() async throws -> TelemetryResponse` | Retrieves telemetry data from the local device |
| `getStatsCore() async throws -> CoreStats` | Retrieves core device statistics including uptime and system metrics |
| `getStatsRadio() async throws -> RadioStats` | Retrieves radio statistics including RSSI, SNR, and transmission counts |
| `getStatsPackets() async throws -> PacketStats` | Retrieves packet statistics including sent, received, and dropped counts |

### Custom Variables

| Method | Description |
|--------|-------------|
| `getCustomVars() async throws -> [String: String]` | Retrieves all custom variables stored on the device |
| `setCustomVar(key:value:) async throws` | Sets a custom variable on the device (key max 32 bytes, value max 256 bytes) |

### Cryptographic Keys

| Method | Description |
|--------|-------------|
| `exportPrivateKey() async throws -> Data` | Exports the device's 32-byte private key (sensitive operation) |
| `importPrivateKey(_:) async throws` | Imports a 64-byte expanded private key, replacing the device's identity |

### Channels

| Method | Description |
|--------|-------------|
| `getChannel(index:) async throws -> ChannelInfo` | Retrieves configuration for a channel (index 0-15) |
| `setChannel(index:name:secret:) async throws` | Configures a channel with name and PSK secret data (16 bytes) |
| `setChannel(index:name:secret:) async throws` | Configures a channel with automatic secret derivation (accepts `ChannelSecret`, default: `.deriveFromName`) |
| `setFloodScope(scopeKey:) async throws` | Sets the flood scope using a raw 32-byte scope key |
| `setFloodScope(_:) async throws` | Sets the flood scope using a `FloodScope` enum |

### Signing

| Method | Description |
|--------|-------------|
| `signStart() async throws -> Int` | Begins a signing operation, returns maximum data size in bytes |
| `signData(_:) async throws` | Sends a data chunk for signing (typically up to 120 bytes) |
| `signFinish(timeout:) async throws -> Data` | Finalizes signing and retrieves the signature (default 3x timeout) |
| `sign(_:chunkSize:timeout:) async throws -> Data` | Signs data using the device's private key (handles complete workflow) |

### Control and Discovery

| Method | Description |
|--------|-------------|
| `sendControlData(type:payload:) async throws` | Sends control data to the mesh network |
| `sendNodeDiscoverRequest(filter:prefixOnly:tag:since:) async throws -> UInt32` | Sends a node discovery request, returns the tag for correlating responses |
| `factoryReset() async throws` | Performs a factory reset (irreversible, erases all data) |

### Remote Node Queries (Binary Protocol)

| Method | Description |
|--------|-------------|
| `requestStatus(from:) async throws -> StatusResponse` | Requests status from a remote node (accepts `Data` or `Destination`) |
| `requestTelemetry(from:) async throws -> TelemetryResponse` | Requests telemetry from a remote node (accepts `Data` or `Destination`) |
| `requestMMA(from:start:end:) async throws -> MMAResponse` | Requests Min-Max-Average data for a time range from a remote node |
| `requestACL(from:) async throws -> ACLResponse` | Requests the Access Control List from a remote node |
| `requestNeighbours(from:count:offset:orderBy:pubkeyPrefixLength:) async throws -> NeighboursResponse` | Requests neighbor list from a remote node with pagination |
| `fetchAllNeighbours(from:orderBy:pubkeyPrefixLength:) async throws -> NeighboursResponse` | Fetches complete neighbor table from a remote node with automatic pagination |

---

## MeshTransport (public, protocol)

**File:** `MeshCore/Sources/MeshCore/Transport/MeshTransport.swift`

Abstraction for underlying transport layers, enabling different implementations for production and testing. The protocol itself is not an actor, but implementations should be actors or otherwise thread-safe (conforming to `Sendable`).

```swift
public protocol MeshTransport: Sendable {
    var receivedData: AsyncStream<Data> { get async }
    var isConnected: Bool { get async }
    func connect() async throws
    func disconnect() async
    func send(_ data: Data) async throws
    // Unacknowledged write (ATT Write Command); default impl routes to send(_:).
    func sendWithoutResponse(_ data: Data) async throws
    // Capability flags; default to false / supportsWriteWithoutResponse.
    var supportsWriteWithoutResponse: Bool { get async }
    var supportsPipelinedReads: Bool { get async }
}
```

### Implementations

| Type | Description |
|------|-------------|
| `WiFiTransport` (public, actor) | Network framework-based transport for WiFi devices |
| `MockTransport` (public, actor) | Deterministic transport for unit testing |

Bluetooth Low Energy is implemented at the app layer: `iOSBLETransport` in MC1Services conforms to `MeshTransport` over CoreBluetooth.

### WiFi Transport Implementation

WiFi transport provides connection to MeshCore devices over TCP/IP networks.

**File:** `MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift`

**Connection Characteristics:**
- **Protocol:** TCP over WiFi
- **Port:** Configurable (the MeshCore One app UI defaults to 5000)
- **Timeout:** 10 seconds (see `WiFiTransport.connectionTimeout`)
- **Framing:** Length-prefixed frames (see `WiFiFrameCodec`)
- **Keep-Alive / Auto-Reconnect:** Not implemented in `WiFiTransport` itself; higher layers can reconnect as needed

**WiFi-Specific Behavior:**

| Aspect | Description |
|---------|-------------|
| **Throughput** | Higher than BLE (~1KB/sec vs ~250 bytes/sec) |
| **Latency** | Higher than BLE (~100-200ms vs ~50-100ms) |
| **Range** | Longer than BLE (~100-300m vs ~10-50m) |
| **Power** | Constant power supply (not battery-dependent) |
| **Discovery** | Manual host/port configuration |
| **Use Cases** | Fixed installations, repeaters, room servers |

**WiFi vs BLE Transport Decision:**

```swift
// Configure before connecting
let transport = WiFiTransport()
await transport.setConnectionInfo(host: host, port: port)
try await transport.connect()
```

**Error Handling:**
- `connect()` throws `WiFiTransportError` (e.g., `.notConfigured`, `.connectionTimeout`, `.connectionFailed(...)`)
- `send(_:)` throws `WiFiTransportError.notConnected` / `.sendFailed(...)`

---

## EventDispatcher (public, actor)

**File:** `MeshCore/Sources/MeshCore/Events/EventDispatcher.swift`

Broadcasts `MeshEvent`s to multiple subscribers via `AsyncStream`. Manages event distribution from the session to all listeners. Uses bounded buffering (100 events) to prevent memory issues.

### Methods

| Method | Description |
|--------|-------------|
| `subscribe() -> AsyncStream<MeshEvent>` | Returns a new unfiltered stream for receiving all events |
| `subscribe(filter:) -> AsyncStream<MeshEvent>` | Returns a filtered stream that only yields matching events |
| `dispatch(_:)` | Synchronously broadcasts an event to all subscribers (not async) |

---

## MeshEvent (public, enum)

**File:** `MeshCore/Sources/MeshCore/Events/MeshEvent.swift`

Represents any event received from the device. Events are organized into categories.

### Connection Lifecycle

| Case | Payload | Description |
|------|---------|-------------|
| `.connectionStateChanged` | `ConnectionState` | Connection state has changed |

### Command Responses

| Case | Payload | Description |
|------|---------|-------------|
| `.ok` | `value: UInt32?` | Command completed successfully |
| `.error` | `code: UInt8?` | Command failed with error |

### Device Information

| Case | Payload | Description |
|------|---------|-------------|
| `.selfInfo` | `SelfInfo` | Device self-information received |
| `.deviceInfo` | `DeviceCapabilities` | Device capabilities received |
| `.battery` | `BatteryInfo` | Battery status received |
| `.currentTime` | `Date` | Current device time received |
| `.customVars` | `[String: String]` | Custom variables received |
| `.channelInfo` | `ChannelInfo` | Channel configuration received |
| `.statsCore` | `CoreStats` | Core statistics received |
| `.statsRadio` | `RadioStats` | Radio statistics received |
| `.statsPackets` | `PacketStats` | Packet statistics received |
| `.autoAddConfig` | `AutoAddConfig` | Auto-add configuration received (v1.12+) |
| `.defaultFloodScope` | `DefaultFloodScope?` | Persisted default flood scope (firmware v11+); `nil` = none persisted |
| `.allowedRepeatFreq` | `[FrequencyRange]` | Allowed repeat frequency ranges (v9+ firmware) |

### Contact Management

| Case | Payload | Description |
|------|---------|-------------|
| `.contactsStart` | `count: Int` | Contact list transfer started |
| `.contact` | `MeshContact` | A contact was received |
| `.contactsEnd` | `lastModified: Date` | Contact list transfer completed |
| `.newContact` | `MeshContact` | A new contact was discovered |
| `.contactDeleted` | `publicKey: Data` | A contact was auto-deleted by the device (storage limit) |
| `.contactsFull` | - | The device's contact storage is full |
| `.contactURI` | `String` | Contact URI was received |

### Messaging

| Case | Payload | Description |
|------|---------|-------------|
| `.messageSent` | `MessageSentInfo` | Message was queued for sending |
| `.contactMessageReceived` | `ContactMessage` | Direct message received from a contact |
| `.channelMessageReceived` | `ChannelMessage` | Channel broadcast message received |
| `.channelDataReceived` | `ChannelDatagram` | Binary datagram received on a channel (firmware v11+) |
| `.noMoreMessages` | - | No more messages waiting |
| `.messagesWaiting` | - | Messages are waiting to be fetched |

### Network Events

| Case | Payload | Description |
|------|---------|-------------|
| `.advertisement` | `publicKey: Data` | Advertisement received from a node |
| `.pathUpdate` | `publicKey: Data` | Routing path was updated |
| `.acknowledgement` | `code: Data, tripTime: UInt32?` | Message delivery acknowledgement (`tripTime` is firmware round-trip ms, if available) |
| `.traceData` | `TraceInfo` | Trace route data received |
| `.pathResponse` | `PathInfo` | Path discovery response |

### Authentication

| Case | Payload | Description |
|------|---------|-------------|
| `.loginSuccess` | `LoginInfo` | Login succeeded |
| `.loginFailed` | `publicKeyPrefix: Data?` | Login failed |

### Binary Protocol Responses

| Case | Payload | Description |
|------|---------|-------------|
| `.statusResponse` | `StatusResponse` | Status response from remote node |
| `.telemetryResponse` | `TelemetryResponse` | Telemetry response from remote node |
| `.binaryResponse` | `tag: Data, data: Data` | Generic binary protocol response |
| `.mmaResponse` | `MMAResponse` | Min/Max/Average telemetry response |
| `.aclResponse` | `ACLResponse` | Access control list response |
| `.neighboursResponse` | `NeighboursResponse` | Neighbours list response |

### Cryptographic Signing

| Case | Payload | Description |
|------|---------|-------------|
| `.signStart` | `maxLength: Int` | Signing session started |
| `.signature` | `Data` | Cryptographic signature generated |
| `.disabled` | `reason: String` | Feature is disabled |

### Raw Data and Logging

| Case | Payload | Description |
|------|---------|-------------|
| `.rawData` | `RawDataInfo` | Raw radio data received |
| `.rxLogData` | `ParsedRxLogData` | Emitted when low-level radio log data is successfully parsed into structured packet information |
| `.logData` | `LogDataInfo` | Emitted for diagnostic logs and malformed RX payloads that failed parsing |
| `.controlData` | `ControlDataInfo` | Control protocol data received |
| `.discoverResponse` | `DiscoverResponse` | Node discovery response |
| `.advertPathResponse` | `AdvertPathResponse` | Advertisement path response (0x16) |
| `.tuningParamsResponse` | `TuningParamsResponse` | Tuning parameters response (0x17) |

### Key Management

| Case | Payload | Description |
|------|---------|-------------|
| `.privateKey` | `Data` | Private key was exported |

### Debug and Diagnostics

| Case | Payload | Description |
|------|---------|-------------|
| `.parseFailure` | `data: Data, reason: String` | Packet parsing failed |

---

## Models

### MeshContact (public, struct)

**File:** `MeshCore/Sources/MeshCore/Models/Contact.swift`

Represents a contact in the mesh network.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Unique identifier (hex string of the public key) |
| `publicKey` | `Data` | 32-byte public key |
| `type` | `ContactType` | Node type: `.chat` (0x01), `.repeater` (0x02), `.room` (0x03) |
| `typeRawValue` | `UInt8` | Raw 1-byte wire type, preserved for types not modeled by `ContactType` |
| `flags` | `ContactFlags` | Contact flags |
| `outPathLength` | `UInt8` | Encoded path-length byte (`0xFF` = flood; upper 2 bits = hash-size mode, lower 6 bits = hop count) |
| `outPath` | `Data` | Routing information |
| `advertisedName` | `String` | Display name from advertisement |
| `lastAdvertisement` | `Date` | Last advertisement timestamp |
| `latitude` | `Double` | Location latitude |
| `longitude` | `Double` | Location longitude |
| `lastModified` | `Date` | Last modification timestamp |

### ContactMessage (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/MessagingPayloads.swift`

Represents a direct message from a contact.

| Property | Type | Description |
|----------|------|-------------|
| `senderPublicKeyPrefix` | `Data` | 6-byte sender key prefix |
| `pathLength` | `UInt8` | Hop count |
| `textType` | `UInt8` | Message type (0=plain, 1=CLI, 2=signed) |
| `senderTimestamp` | `Date` | Sender's timestamp |
| `signature` | `Data?` | Optional message signature |
| `text` | `String` | Message content |
| `snr` | `Double?` | Signal-to-noise ratio |

### ChannelMessage (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/MessagingPayloads.swift`

Represents a message received on a channel.

| Property | Type | Description |
|----------|------|-------------|
| `channelIndex` | `UInt8` | Channel slot |
| `pathLength` | `UInt8` | Hop count |
| `textType` | `UInt8` | Message type |
| `senderTimestamp` | `Date` | Sender's timestamp |
| `text` | `String` | Message content (format: "NodeName: text") |
| `snr` | `Double?` | Signal-to-noise ratio |

---

## Supporting Types

### SessionConfiguration (public, struct)

**File:** `MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift`

Configuration options for `MeshCoreSession`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `defaultTimeout` | `TimeInterval` | `5.0` | Default timeout for operations |
| `clientIdentifier` | `String` | `"MeshCore-Swift"` | Client identifier string |

### OtherParamsConfig (public, struct)

**File:** `MeshCore/Sources/MeshCore/Session/OtherParamsConfig.swift`

Configuration for device "other params" settings. Used by granular configuration setters to implement read-modify-write pattern.

| Property | Type | Description |
|----------|------|-------------|
| `manualAddContacts` | `Bool` | Whether contacts require manual approval |
| `telemetryModeBase` | `UInt8` | Base telemetry mode (0-3) |
| `telemetryModeLocation` | `UInt8` | Location telemetry mode (0-3) |
| `telemetryModeEnvironment` | `UInt8` | Environment telemetry mode (0-3) |
| `advertisementLocationPolicy` | `UInt8` | Location advertising policy |
| `multiAcks` | `UInt8` | Number of acknowledgment retries |

### MessageResult (public, enum)

**File:** `MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift`

Result of a message fetch operation.

| Case | Description |
|------|-------------|
| `.contactMessage(ContactMessage)` | A contact message was received |
| `.channelMessage(ChannelMessage)` | A channel message was received |
| `.channelDatagram(ChannelDatagram)` | A binary datagram was received on a channel (firmware v11+) |
| `.noMoreMessages` | No more messages available in queue |

### Destination (public, enum)

**File:** `MeshCore/Sources/MeshCore/Models/Destination.swift`

Represents the destination for a message. Can be specified as raw data, hex string, or contact object.

| Case | Payload | Description |
|------|---------|-------------|
| `.data(Data)` | Raw bytes | Direct message using raw public key data |
| `.hexString(String)` | Hex string | Direct message using hex-encoded public key |
| `.contact(MeshContact)` | Contact object | Direct message to a contact |

**Methods:**
- `publicKey(prefixLength:) throws -> Data` - Returns the public key prefix of specified length (default 6 bytes)
- `fullPublicKey() throws -> Data` - Returns the full 32-byte public key

### FloodScope (public, enum)

**File:** `MeshCore/Sources/MeshCore/Models/Destination.swift`

Defines the scope for flood routing in the mesh network.

| Case | Payload | Description |
|------|---------|-------------|
| `.disabled` | - | Flood routing is disabled |
| `.channelName(String)` | Channel name | Scope derived from channel name hash |
| `.rawKey(Data)` | 16-byte key | Scope using explicit key |
| `.region(String)` | Region name | Scope from `SHA256("#" + name).prefix(16)`; `#` prefix added automatically |

**Methods:**
- `scopeKey() -> Data` - Generates a 16-byte scope key

### ChannelSecret (public, enum)

**File:** `MeshCore/Sources/MeshCore/Models/Destination.swift`

Defines the secret used for channel encryption.

| Case | Payload | Description |
|------|---------|-------------|
| `.explicit(Data)` | 16-byte secret | Explicit secret key |
| `.deriveFromName` | - | Secret derived from channel name |

**Methods:**
- `secretData(channelName:) -> Data` - Generates 16-byte secret data

### EventFilter (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/EventFilter.swift`

Provides type-safe event filtering for `MeshEvent` values. Use with `EventDispatcher.subscribe(filter:)` or `waitForEvent(filter:timeout:)`.

**Factory methods:**
- `.acknowledgement(code:)` - Filter for specific acknowledgement codes
- `.contactMessage(fromPrefix:)` - Filter messages from a specific sender
- `.channelMessage(channel:)` - Filter messages on a specific channel
- `.statusResponse(fromPrefix:)` - Filter status responses from a node
- `.telemetryResponse(fromPrefix:)` - Filter telemetry responses from a node
- `.advertisement(fromPrefix:)` - Filter advertisements from a node
- `.pathUpdate(forPrefix:)` - Filter path updates for a node
- `.ok` - Filter for `.ok` responses
- `.error` - Filter for `.error` responses
- `.noMoreMessages` - Filter for no-more-messages events
- `.messagesWaiting` - Filter for messages-waiting events

**Combinators:**
- `or(_:)` - Combine with OR logic
- `and(_:)` - Combine with AND logic
- `negated` - Invert the filter

### MeshCoreError (public, enum)

**File:** `MeshCore/Sources/MeshCore/Session/SessionConfiguration.swift`

Errors that can occur during mesh operations.

| Case | Description |
|------|-------------|
| `.timeout` | Operation timed out |
| `.deviceError(code: UInt8)` | The device returned an error code |
| `.parseError(String)` | Failed to parse data from the device |
| `.notConnected` | The transport is not connected |
| `.commandFailed(CommandCode, reason: String)` | A command failed on the device |
| `.invalidResponse(expected: String, got: String)` | Received an unexpected response from the device |
| `.contactNotFound(publicKeyPrefix: Data)` | Could not find the specified contact |
| `.dataTooLarge(maxSize: Int, actualSize: Int)` | The data exceeds the device's maximum allowed size |
| `.signingFailed(reason: String)` | Cryptographic signing failed |
| `.invalidInput(String)` | Provided input is invalid |
| `.unknown(String)` | An unknown error occurred |
| `.bluetoothUnavailable` | Bluetooth is unavailable on this device |
| `.bluetoothUnauthorized` | App is not authorized to use Bluetooth |
| `.bluetoothPoweredOff` | Bluetooth is powered off |
| `.connectionLost(underlying: Error?)` | The connection was lost |
| `.sessionNotStarted` | The session has not been started |
| `.featureDisabled` | The requested feature is disabled on the device |

### SelfInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Models/DeviceInfo.swift`

Information about the local device, received after session start.

| Property | Type | Description |
|----------|------|-------------|
| `advertisementType` | `UInt8` | The type of advertisement used by the device |
| `txPower` | `Int8` | The current transmit power level |
| `maxTxPower` | `Int8` | The maximum supported transmit power level |
| `publicKey` | `Data` | The node's 32-byte public key |
| `latitude` | `Double` | The current latitude coordinate |
| `longitude` | `Double` | The current longitude coordinate |
| `multiAcks` | `UInt8` | Whether multiple acknowledgments are enabled |
| `advertisementLocationPolicy` | `UInt8` | The policy for location sharing in advertisements |
| `telemetryModeEnvironment` | `UInt8` | The environment telemetry reporting mode |
| `telemetryModeLocation` | `UInt8` | The location telemetry reporting mode |
| `telemetryModeBase` | `UInt8` | The base telemetry reporting mode |
| `manualAddContacts` | `Bool` | Whether contacts must be added manually |
| `radioFrequency` | `Double` | The radio center frequency in MHz |
| `radioBandwidth` | `Double` | The radio bandwidth in kHz |
| `radioSpreadingFactor` | `UInt8` | The radio spreading factor |
| `radioCodingRate` | `UInt8` | The radio coding rate |
| `name` | `String` | The user-defined name for this device |

### DeviceCapabilities (public, struct)

**File:** `MeshCore/Sources/MeshCore/Models/DeviceInfo.swift`

Hardware and firmware capabilities of the device.

| Property | Type | Description |
|----------|------|-------------|
| `firmwareVersion` | `UInt8` | Firmware version number |
| `maxContacts` | `Int` | Maximum contact storage |
| `maxChannels` | `Int` | Maximum channels supported |
| `blePin` | `UInt32` | BLE PIN code |
| `firmwareBuild` | `String` | Firmware build string |
| `model` | `String` | Hardware model identifier |
| `version` | `String` | Version string |

### BatteryInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Models/DeviceInfo.swift`

Battery status information from the device.

| Property | Type | Description |
|----------|------|-------------|
| `level` | `Int` | Battery level in millivolts |
| `usedStorageKB` | `Int?` | Used storage in kilobytes |
| `totalStorageKB` | `Int?` | Total storage in kilobytes |

### MessageSentInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/MessagingPayloads.swift`

Information returned after successfully sending a message.

| Property | Type | Description |
|----------|------|-------------|
| `route` | `UInt8` | Route flag from firmware: 1 = flood, 0 = direct |
| `expectedAck` | `Data` | Expected acknowledgment code for this message |
| `suggestedTimeoutMs` | `UInt32` | Suggested timeout in milliseconds for waiting for ACK |

### ChannelInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/ChannelPayloads.swift`

Configuration information for a broadcast channel.

| Property | Type | Description |
|----------|------|-------------|
| `index` | `UInt8` | Channel index (0-15) |
| `name` | `String` | Human-readable channel name |
| `secret` | `Data` | Secret key data for channel encryption |

### CoreStats (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/StatsPayloads.swift`

Core device statistics from the local device.

| Property | Type | Description |
|----------|------|-------------|
| `batteryMV` | `UInt16` | Battery level in millivolts |
| `uptimeSeconds` | `UInt32` | Device uptime in seconds |
| `errors` | `UInt16` | Total error count |
| `queueLength` | `UInt8` | Current transmit queue length |

### RadioStats (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/StatsPayloads.swift`

Radio statistics from the local device.

| Property | Type | Description |
|----------|------|-------------|
| `noiseFloor` | `Int16` | Noise floor in dBm |
| `lastRSSI` | `Int8` | Last received signal strength in dBm |
| `lastSNR` | `Double` | Last signal-to-noise ratio in dB |
| `txAirtimeSeconds` | `UInt32` | Total transmit airtime in seconds |
| `rxAirtimeSeconds` | `UInt32` | Total receive airtime in seconds |

### PacketStats (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/StatsPayloads.swift`

Packet statistics from the local device.

| Property | Type | Description |
|----------|------|-------------|
| `received` | `UInt32` | Total packets received |
| `sent` | `UInt32` | Total packets sent |
| `floodTx` | `UInt32` | Total flood packets transmitted |
| `directTx` | `UInt32` | Total direct packets transmitted |
| `floodRx` | `UInt32` | Total flood packets received |
| `directRx` | `UInt32` | Total direct packets received |

### StatusResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DevicePayloads.swift`

Status response from a remote node via binary protocol.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of responding node |
| `battery` | `Int` | Battery level in millivolts |
| `txQueueLength` | `Int` | Current transmit queue length |
| `noiseFloor` | `Int` | Noise floor in dBm |
| `lastRSSI` | `Int` | Last RSSI in dBm |
| `packetsReceived` | `UInt32` | Total packets received |
| `packetsSent` | `UInt32` | Total packets sent |
| `airtime` | `UInt32` | Total TX airtime in seconds |
| `uptime` | `UInt32` | Node uptime in seconds |
| `sentFlood` | `UInt32` | Flood packets sent |
| `sentDirect` | `UInt32` | Direct packets sent |
| `receivedFlood` | `UInt32` | Flood packets received |
| `receivedDirect` | `UInt32` | Direct packets received |
| `fullEvents` | `Int` | Total full events recorded |
| `lastSNR` | `Double` | Last SNR in dB |
| `directDuplicates` | `Int` | Direct duplicates received |
| `floodDuplicates` | `Int` | Flood duplicates received |
| `rxAirtime` | `UInt32` | Total RX airtime in seconds |

### TelemetryResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Telemetry response from a remote node via binary protocol.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of responding node |
| `tag` | `Data?` | Optional correlation tag |
| `rawData` | `Data` | Raw telemetry data payload |
| `dataPoints` | `[LPPDataPoint]` | Parsed Cayenne LPP data points (computed property) |

### MMAResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Min-Max-Average response from a remote node.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of responding node |
| `tag` | `Data` | Correlation tag |
| `data` | `[MMAEntry]` | List of MMA entries |

### MMAEntry (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Single entry in MMA response data.

| Property | Type | Description |
|----------|------|-------------|
| `channel` | `UInt8` | Sensor channel |
| `type` | `String` | Data type |
| `min` | `Double` | Minimum value |
| `max` | `Double` | Maximum value |
| `avg` | `Double` | Average value |

### ACLResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Access Control List response from a remote node.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of responding node |
| `tag` | `Data` | Correlation tag |
| `entries` | `[ACLEntry]` | List of ACL entries |

### ACLEntry (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Single entry in ACL response data.

| Property | Type | Description |
|----------|------|-------------|
| `keyPrefix` | `Data` | Public key prefix affected by this ACL entry |
| `permissions` | `UInt8` | Permissions granted |

### NeighboursResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Neighbour list response from a remote node.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of responding node |
| `tag` | `Data` | Correlation tag |
| `totalCount` | `Int` | Total number of neighbours known to the node |
| `neighbours` | `[Neighbour]` | List of neighbours in this response |

### Neighbour (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Information about a neighbouring node.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of neighbour |
| `secondsAgo` | `Int` | Seconds since last seen |
| `snr` | `Double` | Signal-to-noise ratio of last communication |

### ConnectionState (public, enum)

**File:** `MeshCore/Sources/MeshCore/Events/ConnectionState.swift`

Represents the current connection state of a MeshCore session.

| Case | Payload | Description |
|------|---------|-------------|
| `.disconnected` | - | Session is disconnected |
| `.connecting` | - | Session is attempting to connect |
| `.connected` | - | Session is successfully connected |
| `.reconnecting` | `attempt: Int` | Session is attempting to reconnect after failure |
| `.failed` | `MeshTransportError` | Connection failed with error |

### MeshTransportError (public, enum)

**File:** `MeshCore/Sources/MeshCore/Events/MeshTransportError.swift`

Errors that can occur at the transport layer.

| Case | Payload | Description |
|------|---------|-------------|
| `.notConnected` | - | Transport is not connected |
| `.connectionFailed` | `String` | Connection attempt failed with reason |
| `.sendFailed` | `String` | Sending data failed with reason |
| `.deviceNotFound` | - | Target device could not be found |
| `.serviceNotFound` | - | Required service not found on device |
| `.characteristicNotFound` | - | Required characteristic not found on device |

### TraceInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

Trace route information received from the mesh network.

| Property | Type | Description |
|----------|------|-------------|
| `tag` | `UInt32` | Request correlation tag |
| `authCode` | `UInt32` | Authentication code for the trace request |
| `flags` | `UInt8` | Configuration flags for the trace |
| `pathLength` | `UInt8` | Length of the recorded path |
| `path` | `[TraceNode]` | List of nodes in the trace path |

### TraceNode (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

A node in a trace path.

| Property | Type | Description |
|----------|------|-------------|
| `hashBytes` | `Data?` | Hash bytes of the node's public key (1, 2, 4, or 8 bytes per path_sz; nil for destination) |
| `hash` | `UInt8?` | Legacy accessor: first byte of `hashBytes`, or nil |
| `snr` | `Double` | Signal-to-noise ratio at this hop |

### PathInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

Path discovery information.

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | Public key prefix of the destination node |
| `outPathLength` | `UInt8` | Raw outbound `path_len` byte (upper 2 bits = hash mode, lower 6 = hop count) |
| `outPath` | `Data` | Outbound path data |
| `inPathLength` | `UInt8` | Raw inbound `path_len` byte |
| `inPath` | `Data` | Inbound path data |

### LoginInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/RemoteAdminPayloads.swift`

Login success information.

| Property | Type | Description |
|----------|------|-------------|
| `permissions` | `UInt8` | Permissions granted after login |
| `isAdmin` | `Bool` | Whether the user has administrator privileges |
| `publicKeyPrefix` | `Data` | Public key prefix of the node where login occurred |

### RawDataInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

Raw data received from the device.

| Property | Type | Description |
|----------|------|-------------|
| `snr` | `Double` | Signal-to-noise ratio of the received packet |
| `rssi` | `Int` | Received signal strength indicator in dBm |
| `payload` | `Data` | Raw payload data |

### LogDataInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

Log data received from the device.

| Property | Type | Description |
|----------|------|-------------|
| `snr` | `Double?` | Optional signal-to-noise ratio |
| `rssi` | `Int?` | Optional received signal strength indicator |
| `payload` | `Data` | Raw log payload data |

### ParsedRxLogData (public, struct)

**File:** `MeshCore/Sources/MeshCore/Protocol/RxLogTypes.swift`

Parsed RF packet data from `rxLogData` events.

| Property | Type | Description |
|----------|------|-------------|
| `snr` | `Double?` | SNR in dB (0.25 dB resolution) |
| `rssi` | `Int?` | RSSI in dBm |
| `rawPayload` | `Data` | Original unparsed payload bytes |
| `routeType` | `RouteType` | Routing method (flood, direct, tc_flood, tc_direct) |
| `payloadType` | `PayloadType` | Message type (header bits 2-5; values 12-14 map to `.unknown`, 15 maps to `.rawCustom`) |
| `payloadVersion` | `UInt8` | Protocol version (0-3) |
| `transportCode` | `Data?` | 4-byte transport code if route type requires it |
| `pathLength` | `UInt8` | Number of path nodes |
| `pathNodes` | `[UInt8]` | Path node identifiers (1 byte each) as emitted by firmware |
| `packetPayload` | `Data` | Payload after header/path extraction |
| `packetHash` | `String` | First 8 bytes of SHA-256 of `packetPayload`, as 16-char hex |
| `senderPubkeyPrefix` | `Data?` | 1-byte sender pubkey hash for direct text messages |

### ControlDataInfo (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/DiagnosticsPayloads.swift`

Control protocol data received from the device.

| Property | Type | Description |
|----------|------|-------------|
| `snr` | `Double` | Signal-to-noise ratio of the received packet |
| `rssi` | `Int` | Received signal strength indicator in dBm |
| `pathLength` | `UInt8` | Path length the control packet travelled |
| `payloadType` | `UInt8` | Type of control protocol payload |
| `payload` | `Data` | Raw payload data |

### DiscoverResponse (public, struct)

**File:** `MeshCore/Sources/MeshCore/Events/ContactPayloads.swift`

Node discovery response.

| Property | Type | Description |
|----------|------|-------------|
| `nodeType` | `UInt8` | Type of the discovered node |
| `snrIn` | `Double` | Inbound signal-to-noise ratio |
| `snr` | `Double` | Signal-to-noise ratio |
| `rssi` | `Int` | Received signal strength indicator in dBm |
| `pathLength` | `UInt8` | Path length to the discovered node |
| `tag` | `Data` | Request correlation tag |
| `publicKey` | `Data` | Full public key of the discovered node |

---

## Utilities

### PacketBuilder (public, enum)

**File:** `MeshCore/Sources/MeshCore/Protocol/PacketBuilder.swift`

Stateless enum for constructing binary protocol packets.

### PacketParser (public, enum)

**File:** `MeshCore/Sources/MeshCore/Protocol/PacketParser.swift`

Stateless enum for parsing binary protocol packets into `MeshEvent`s.

### LPPDecoder (public, enum)

**File:** `MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift`

Decodes Cayenne Low Power Payload (LPP) telemetry data.

| Method | Description |
|--------|-------------|
| `decode(_:) -> [LPPDataPoint]` | Decodes raw LPP bytes into data points |

### LPPDataPoint (public, struct)

**File:** `MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift`

Represents a single decoded LPP data point.

| Property | Type | Description |
|----------|------|-------------|
| `channel` | `UInt8` | Channel identifier (application-specific) |
| `type` | `LPPSensorType` | The sensor type |
| `value` | `LPPValue` | The decoded value |

### LPPSensorType (public, enum)

**File:** `MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift`

Cayenne LPP sensor types. Includes `temperature`, `humidity`, `barometer`, `voltage`, `gps`, and many more.

### LPPValue (public, enum)

**File:** `MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift`

Decoded LPP sensor values.

| Case | Payload | Description |
|------|---------|-------------|
| `.digital` | `Bool` | Boolean value (digital I/O, presence, switch) |
| `.integer` | `Int` | Integer value (illuminance, percentage, direction) |
| `.float` | `Double` | Floating-point value |
| `.vector3` | `x, y, z: Double` | 3D vector (accelerometer, gyrometer) |
| `.gps` | `latitude, longitude, altitude: Double` | GPS coordinates |
| `.rgb` | `red, green, blue: UInt8` | RGB colour |
| `.timestamp` | `Date` | Unix timestamp |

---

## See Also

- [Architecture Overview](../Architecture.md)
- [BLE Transport Guide](../guides/BLE_Transport.md)
- [WiFi Transport Guide](../guides/WiFi_Transport.md)
- [Messaging Guide](../guides/Messaging.md)
