# MC1Services API Reference

The `MC1Services` layer provides actor-isolated business logic, managing services, persistence, and device connections.

## Package Information

- **Location:** `MC1Services/`
- **Type:** Swift Package (single library target)
- **Dependencies:** MeshCore

---

## ConnectionManager (public, @MainActor, @Observable class)

**File:** `MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift` (split across `Connection/ConnectionManager+Lifecycle.swift`, `+BLE.swift`, `+Pairing.swift`, `+WiFi.swift`, and `Sync/ConnectionManager+SyncRetry.swift`)

The primary entry point for managing the connection to a MeshCore device and coordinating services.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `DeviceConnectionState` | Current state: `.disconnected`, `.connecting`, `.connected`, `.syncing`, `.ready` |
| `connectedDevice` | `DeviceDTO?` | Currently connected device info |
| `services` | `ServiceContainer?` | Business logic services (available when `.ready`) |
| `currentTransportType` | `TransportType?` | Active transport type (`.bluetooth` or `.wifi`) |

### Methods

| Method | Description |
|--------|-------------|
| `activate() async` | Initializes and attempts auto-reconnect to last device |
| `pairNewDevice() async throws` | Starts AccessorySetupKit pairing flow |
| `connect(to:forceFullSync:forceReconnect:) async throws` | Connects to a previously paired device |
| `disconnect(reason:) async` | Gracefully disconnects and stops services |
| `forgetDevice(deleteData:) async throws` | Removes device from app and system pairings |
| `forgetDevice(id:) async` | Removes a device by ID (non-throwing) |
| `switchDevice(to:) async throws` | Switches to a different device |
| `connectViaWiFi(host:port:forceFullSync:) async throws` | Connects to a device over WiFi/TCP |
| `clearStalePairings() async` | Clears all stale pairings from AccessorySetupKit |
| `fetchSavedDevices() async throws -> [DeviceDTO]` | Fetches all previously paired devices from storage |
| `hasAccessory(for:) -> Bool` | Checks if an accessory is registered with AccessorySetupKit |
| `renameCurrentDevice() async throws` | Renames the currently connected device via AccessorySetupKit |

### Additional Properties

| Property | Type | Description |
|----------|------|-------------|
| `pairedAccessoriesCount` | `Int` | Number of paired accessories (for troubleshooting UI) |
| `pairedAccessoryInfos` | `[(id: UUID, name: String)]` | Returns paired accessories from AccessorySetupKit |
| `lastConnectedDeviceID` | `UUID?` | Last device ID stored for auto-reconnect |
| `onConnectionReady` | `(() async -> Void)?` | Called when connection is ready and services are available |

---

## SyncCoordinator (public, actor)

**File:** `MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift` (split across `Sync/SyncCoordinator+Sync.swift`, `+MessageHandlers.swift`, `+ReactionHandlers.swift`, `+HandlerHelpers.swift`)

Orchestrates data synchronization between the MeshCore device and local database through three phases.

### Sync Phases

```swift
public enum SyncPhase: Sendable, Equatable {
    case contacts   // Phase 1: Sync contacts from device
    case channels   // Phase 2: Sync channel configurations
    case messages   // Phase 3: Poll pending messages
}
```

### Sync State

```swift
enum SyncState: Sendable, Equatable {  // internal, not public
    case idle
    case syncing(progress: SyncProgress)
    case synced
    case failed(SyncCoordinatorError)
}
```

### Key Methods

| Method | Description |
|--------|-------------|
| `performFullSync(radioID:dataStore:contactService:channelService:messagePollingService:...) async throws -> FullSyncResult` | Executes contacts → channels → messages sync (internal) |
| `onConnectionEstablished(radioID:dependencies:...) async throws -> FullSyncResult` | Called after a connection; wires handlers and syncs (internal) |
| `setSyncActivityCallbacks(onStarted:onEnded:onPhaseChanged:) async` | Sets UI callbacks for sync pill display |

### Connection Lifecycle

1. Wire message handlers (before events arrive)
2. Start event monitoring
3. Perform full sync (contacts, channels, messages)
4. Wire discovery handlers (for ongoing contact discovery)

---

## MessageService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/MessageService.swift` (sends split across `MessageService+SendDM.swift`, `+SendChannel.swift`, `+SendHelpers.swift`; ACK tracking in `MessageService+ACK.swift`)

Handles message sending with automatic retry logic, flood routing fallback, and ACK tracking.

### Configuration

```swift
struct MessageServiceConfig: Sendable {  // internal; defined in MessageServiceConfig.swift
    let floodFallbackOnRetry: Bool        // Use flood on manual retry (default: true)
    let maxAttempts: Int                  // Total attempts (default: 5; capped at 5 = 4 direct + 1 flood)
    let maxFloodAttempts: Int             // Max flood attempts (default: 1)
    let floodAfter: Int                   // Switch to flood after N direct attempts (default: 4)
    let minTimeout: TimeInterval          // Minimum timeout seconds (default: 0)
    let triggerPathDiscoveryAfterFlood: Bool // Trigger path discovery after a successful flood
    let ackGiveUpWindow: TimeInterval     // Give-up floor for the ACK deadline on fast presets
    let poolBackoff: PoolBackoffConfig    // In-loop pool-exhaustion backoff tuning
}
```

### Messaging Methods

| Method | Description |
|--------|-------------|
| `sendMessageWithRetry(text:to:...) async throws -> MessageDTO` | Sends with auto-retry and flood fallback |
| `sendDirectMessage(text:to:...) async throws -> MessageDTO` | Single attempt send |
| `sendChannelMessage(text:channelIndex:...) async throws -> (id: UUID, timestamp: UInt32)` | Broadcasts to channel |
| `resendDirectMessage(messageID:to:) async throws -> MessageDTO` | Manual retry of a failed direct message |
| `resendChannelMessage(messageID:preserveTimestamp:) async throws -> UInt32` | Manual retry of a failed channel message (returns the timestamp) |

### Event Monitoring

| Method | Description |
|--------|-------------|
| `startEventMonitoring()` | Starts monitoring session events to process message acknowledgements |
| `stopEventMonitoring()` | Stops monitoring session events |

### ACK Tracking

| Method | Description |
|--------|-------------|
| `startAckExpiryChecking(interval:)` | Starts periodic expired ACK checks (default: 5s; wired on connect via `ServiceContainer.startEventMonitoring`) |
| `stopAckExpiryChecking()` | Stops background ACK checking |
| `checkExpiredAcks() async throws` | Marks expired ACKs' messages as `.failed` and pushes codes into the late-ACK ring |
| `failAllPendingMessages() async throws` | Fails all pending messages that are awaiting ACK |
| `stopAndFailAllPending() async throws` | Stops ACK checking and fails all pending messages atomically |

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `pendingAckCount` | `Int` | Current number of pending ACKs being tracked |
| `isAckExpiryCheckingActive` | `Bool` | Whether ACK expiry checking is currently active |

### Dependencies

The `ContactService` used for path management during retry is injected via `MessageService`'s initializer (no public setter). Status updates flow through `ChatCoordinator`, `MessageStatusEvent`, and the `PersistenceStore` rather than per-callback handler setters on the service.

### Retry Flow

1. Direct routing for the first `floodAfter` attempts (using the contact's outbound path)
2. Flood routing thereafter (broadcast to all nearby nodes), up to `maxAttempts` (capped at 5 = 4 direct + 1 flood)
3. Returns immediately when ACK received
4. Marks failed if all attempts exhausted

---

## ContactService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/ContactService.swift`

Manages discovery, synchronization, and storage of mesh contacts.

### Sync Methods

| Method | Description |
|--------|-------------|
| `syncContacts(radioID:since:) async throws -> ContactSyncResult` | Incremental or full contact sync |

### Contact Management

| Method | Description |
|--------|-------------|
| `getContact(radioID:publicKey:) async throws -> ContactDTO?` | Get a specific contact by public key from local database |
| `addOrUpdateContact(radioID:contact:) async throws` | Adds/updates contact on device and local store |
| `removeContact(radioID:publicKey:) async throws` | Deletes from device and local store |

### Path Discovery & Routing

| Method | Description |
|--------|-------------|
| `sendPathDiscovery(radioID:publicKey:) async throws -> MessageSentInfo` | Initiates route discovery |
| `resetPath(radioID:publicKey:) async throws` | Resets routing, forces mesh rediscovery |
| `setPath(radioID:publicKey:path:pathLength:) async throws` | Set a specific path for a contact |

### Contact Sharing

| Method | Description |
|--------|-------------|
| `shareContact(publicKey:) async throws` | Share a contact via zero-hop broadcast |
| `exportContact(publicKey:) async throws -> String` | Export a contact to a shareable URI |
| `exportContactURI(name:publicKey:type:) -> String` | Build a shareable contact URI (static method) |
| `importContact(cardData:) async throws` | Import a contact from card data |

### Local Database Operations

| Method | Description |
|--------|-------------|
| `getContacts(radioID:) async throws -> [ContactDTO]` | Get all contacts for a device from local database |
| `getConversations(radioID:) async throws -> [ContactDTO]` | Get conversations (contacts with messages) from local database |
| `getContactByID(_:) async throws -> ContactDTO?` | Get a contact by ID from local database |
| `updateContactPreferences(contactID:nickname:isBlocked:isFavorite:) async throws` | Update local contact preferences |

---

## ChannelService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/ChannelService.swift`

Manages group messaging channels and secure slot configuration.

### Sync Methods

| Method | Description |
|--------|-------------|
| `syncChannels(radioID:maxChannels:usePipelinedRead:) async throws -> ChannelSyncResult` | Syncs all channel slot configurations (internal) |

### Channel CRUD Operations

| Method | Description |
|--------|-------------|
| `fetchChannel(index:) async throws -> ChannelInfo?` | Fetches a single channel from the device |
| `setChannel(radioID:index:name:passphrase:) async throws` | Configures slot with passphrase (SHA-256 hashed) |
| `setChannelWithSecret(radioID:index:name:secret:) async throws` | Sets a channel with a pre-computed secret |
| `clearChannel(radioID:index:) async throws` | Resets a channel slot |

### Local Database Operations

| Method | Description |
|--------|-------------|
| `getChannels(radioID:) async throws -> [ChannelDTO]` | Gets all channels from local database for a device |
| `getChannel(radioID:index:) async throws -> ChannelDTO?` | Gets a specific channel from local database |
| `getActiveChannels(radioID:) async throws -> [ChannelDTO]` | Gets channels that have messages (for chat list) |
| `clearChannelMessages(radioID:channelIndex:) async throws` | Deletes local messages for a channel |

### Public Channel (Slot 0)

| Method | Description |
|--------|-------------|
| `setupPublicChannel(radioID:) async throws` | Initializes default public channel on slot 0 |
| `hasPublicChannel(radioID:) async throws -> Bool` | Checks if the public channel exists locally |

### Static Utilities

| Method | Description |
|--------|-------------|
| `hashSecret(_:) -> Data` | Hashes a passphrase into a 16-byte channel secret using SHA-256 |
| `validateSecret(_:) -> Bool` | Validates that a secret has the correct size |

---

## RemoteNodeService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/RemoteNodeService.swift`

Queries remote mesh nodes using the binary protocol.

### Session Management

| Method | Description |
|--------|-------------|
| `createSession(radioID:contact:) async throws -> RemoteNodeSessionDTO` | Create a new session for a remote node |
| `removeSession(id:publicKey:) async throws` | Remove a session and its associated data |
| `hasPassword(forContact:) async -> Bool` | Check if a password is stored for a contact's public key |
| `storePassword(_:forNodeKey:) async throws` | Store a password for a remote node |

### Login & Authentication

| Method | Description |
|--------|-------------|
| `login(sessionID:password:pathLength:) async throws -> LoginResult` | Login to a remote node (works for both room servers and repeaters) |
| `logout(sessionID:) async throws` | Explicitly logout from a remote node |

### Event Monitoring

| Method | Description |
|--------|-------------|
| `startEventMonitoring()` | Start monitoring MeshCore events for login results |
| `stopEventMonitoring()` | Stop monitoring events |

### Keep-Alive (Room Servers)

| Method | Description |
|--------|-------------|
| `sendKeepAlive(sessionID:) async throws` | Send keep-alive (for manual refresh) |

### Remote Node Queries

| Method | Description |
|--------|-------------|
| `requestStatus(sessionID:) async throws -> StatusResponse` | Gets battery, uptime, SNR from remote |
| `requestTelemetry(sessionID:) async throws -> TelemetryResponse` | Gets sensor telemetry from remote |
| `requestHistorySync(sessionID:) async throws` | Request message history from a room server |

### CLI Commands

| Method | Description |
|--------|-------------|
| `sendCLICommand(sessionID:command:) async throws -> String` | Send a CLI command to a remote node (admin only) |

### Connection Management

| Method | Description |
|--------|-------------|
| `disconnect(sessionID:) async` | Mark session as disconnected without sending logout |
| `handleBLEReconnection(sessionIDs:) async` | Called when BLE connection is re-established |
| `stopAllKeepAlives()` | Stop all keep-alive timers (call on app termination) |

### Handlers

| Property | Type | Description |
|----------|------|-------------|
| `keepAliveResponseHandler` | `(@Sendable (UUID, Int) async -> Void)?` | Handler for keep-alive ACK responses |

Note: Neighbor fetching is performed via `MeshCoreSession.fetchAllNeighbours()` directly.

---

## PersistenceStore (public, @ModelActor actor)

**File:** `MC1Services/Sources/MC1Services/Services/PersistenceStore.swift`

Type alias: `DataStore = PersistenceStore`

The unified interface for SwiftData persistence, shared across all services.

### Responsibilities

- CRUD operations for `Device`, `Contact`, `Message`, `Channel`, `RemoteNodeSession`, `RoomMessage` models
- Thread-safe access via actor model
- Uses DTOs for cross-boundary data transfer

### Device Operations

| Method | Description |
|--------|-------------|
| `fetchDevices() throws -> [DeviceDTO]` | Fetch all devices |
| `fetchDevice(id:) throws -> DeviceDTO?` | Fetch a device by ID |
| `fetchActiveDevice() throws -> DeviceDTO?` | Fetch the active device |
| `saveDevice(_:) throws` | Save or update a device |
| `setActiveDevice(id:) throws` | Set a device as active (deactivates others) |
| `deleteDevice(id:) throws` | Delete a device and all its associated data |

### Contact Operations

| Method | Description |
|--------|-------------|
| `fetchContacts(radioID:) throws -> [ContactDTO]` | Fetch all contacts for a device |
| `fetchContact(id:) throws -> ContactDTO?` | Fetch a contact by ID |
| `fetchContact(radioID:publicKey:) throws -> ContactDTO?` | Fetch a contact by public key |
| `fetchConversations(radioID:) throws -> [ContactDTO]` | Fetch contacts with messages |
| `saveContact(_:) throws` | Save or update a contact |
| `saveContact(radioID:from:) throws -> UUID` | Save contact from ContactFrame |
| `deleteContact(id:) throws` | Delete a contact |
| `updateContactLastMessage(contactID:date:) throws` | Update contact's last message date |

### Message Operations

| Method | Description |
|--------|-------------|
| `fetchMessages(contactID:) throws -> [MessageDTO]` | Fetch all messages for a contact |
| `fetchMessages(radioID:channelIndex:) throws -> [MessageDTO]` | Fetch all messages for a channel |
| `fetchMessage(id:) throws -> MessageDTO?` | Fetch a message by ID |
| `saveMessage(_:) throws` | Save or update a message |
| `deleteMessage(id:) throws` | Delete a message |
| `updateMessageStatus(id:status:) throws` | Update message delivery status |
| `updateMessageAck(id:ackCode:status:roundTripTime:) throws` | Update message ACK code, status, and round-trip time |
| `updateMessageRetryStatus(id:status:retryAttempt:maxRetryAttempts:) throws` | Update message retry status |
| `updateMessageHeardRepeats(id:heardRepeats:) throws` | Update message heard repeats count |
| `markMessageAsRead(id:) throws` | Mark a single message as read by message ID |

### Channel Operations

| Method | Description |
|--------|-------------|
| `fetchChannels(radioID:) throws -> [ChannelDTO]` | Fetch all channels for a device |
| `fetchChannel(id:) throws -> ChannelDTO?` | Fetch a channel by ID |
| `fetchChannel(radioID:index:) throws -> ChannelDTO?` | Fetch a channel by index |
| `saveChannel(_:) throws` | Save or update a channel |
| `saveChannel(radioID:from:) throws -> UUID` | Save channel from ChannelInfo |
| `deleteChannel(id:) throws` | Delete a channel |
| `updateChannelLastMessage(channelID:date:) throws` | Update channel's last message date |

### RemoteNodeSession Operations

| Method | Description |
|--------|-------------|
| `fetchRemoteNodeSession(id:) throws -> RemoteNodeSessionDTO?` | Fetch a session by ID |
| `fetchRemoteNodeSession(publicKey:) throws -> RemoteNodeSessionDTO?` | Fetch a session by public key |
| `fetchRemoteNodeSessionByPrefix(_:) throws -> RemoteNodeSessionDTO?` | Fetch a session by public key prefix |
| `fetchConnectedRemoteNodeSessions() throws -> [RemoteNodeSessionDTO]` | Fetch all connected sessions |
| `saveRemoteNodeSessionDTO(_:) throws` | Save or update a session |
| `updateRemoteNodeSessionConnection(id:isConnected:permissionLevel:) throws` | Update session connection state |
| `deleteRemoteNodeSession(id:) throws` | Delete a session |

### Static Methods

| Method | Description |
|--------|-------------|
| `createContainer(inMemory:) throws -> ModelContainer` | Creates a ModelContainer for the app |

---

## Data Transfer Objects

### MessageDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/Message.swift` (search for `public struct MessageDTO`)

A sendable snapshot of `Message` for cross-actor transfers. The DTO evolves as features are added (link previews, reactions, mentions, etc.), so this doc intentionally describes the shape at a high level.

Key fields you can rely on:

- Identity and routing: `id`, `deviceID`, `contactID` or `channelIndex`
- Content and timing: `text`, `timestamp`, `createdAt`, `senderTimestamp` (when available)
- Delivery metadata: `direction`, `status`, `textType`, `ackCode`, `roundTripTime`, `retryAttempt`, `maxRetryAttempts`
- RF / mesh metadata: `pathLength`, `pathNodes`, `snr`, `heardRepeats`, `sendCount`
- Sender identity: `senderKeyPrefix`, `senderNodeName`
- UI flags: `isRead`, `containsSelfMention`, `mentionSeen`, `timestampCorrected`
- Rich content caches: link preview fields and `reactionSummary`

### ContactDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/Contact.swift` (search for `public struct ContactDTO`)

A sendable snapshot of `Contact` for cross-actor transfers.

Notes:

- `latitude` and `longitude` are not optional. Treat `0,0` as "unknown" and use `ContactDTO.hasLocation` (computed) where available.
- Favorites are synced with the device via the `flags` byte (bit 0) and cached as `isFavorite`.

### Computed Properties

| Property | Type | Description |
|----------|------|-------------|
| `type` | `ContactType` | Computed from `typeRawValue` |
| `displayName` | `String` | Returns `nickname` if set, otherwise `name` |
| `publicKeyPrefix` | `Data` | First 6 bytes of public key |

### DeviceDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/Device.swift` (search for `public struct DeviceDTO`)

A sendable snapshot of Device for cross-actor transfers. The DTO carries `radioID` (the data-partition key) in addition to `id`, and gains fields over time (OCV settings, flood scope, connection methods, known regions, repeater pre-repeat radio config), so the table below lists the core fields rather than the full set.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Device identifier |
| `publicKey` | `Data` | Device public key |
| `nodeName` | `String` | Device node name |
| `firmwareVersion` | `UInt8` | Firmware version number |
| `firmwareVersionString` | `String` | Firmware version string |
| `manufacturerName` | `String` | Manufacturer name |
| `buildDate` | `String` | Firmware build date |
| `maxContacts` | `UInt16` | Maximum contacts supported |
| `maxChannels` | `UInt8` | Maximum channels supported |
| `frequency` | `UInt32` | Radio frequency (kHz) |
| `bandwidth` | `UInt32` | Radio bandwidth (Hz) |
| `spreadingFactor` | `UInt8` | LoRa spreading factor |
| `codingRate` | `UInt8` | LoRa coding rate |
| `txPower` | `Int8` | Transmit power (dBm) |
| `maxTxPower` | `Int8` | Maximum transmit power (dBm) |
| `latitude` | `Double` | Device location latitude |
| `longitude` | `Double` | Device location longitude |
| `blePin` | `UInt32` | BLE pairing PIN |
| `manualAddContacts` | `Bool` | Manual contact add mode |
| `multiAcks` | `UInt8` | Multiple ACKs mode |
| `telemetryModeBase` | `UInt8` | Base telemetry mode |
| `telemetryModeLoc` | `UInt8` | Location telemetry mode |
| `telemetryModeEnv` | `UInt8` | Environment telemetry mode |
| `advertLocationPolicy` | `UInt8` | Advertisement location policy |
| `lastConnected` | `Date` | Last connection timestamp |
| `lastContactSync` | `UInt32` | Last contact sync timestamp |
| `isActive` | `Bool` | Active status |

### Computed Properties

| Property | Type | Description |
|----------|------|-------------|
| `publicKeyPrefix` | `Data` | First 6 bytes of public key |

### ChannelDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/Channel.swift` (search for `public struct ChannelDTO`)

A sendable snapshot of Channel for cross-actor transfers. Core fields:

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Local identifier |
| `radioID` | `UUID` | Associated device (data-partition key) |
| `index` | `UInt8` | Slot number (0-7) |
| `name` | `String` | Channel name |
| `secret` | `Data` | Channel encryption secret (16 bytes) |
| `isEnabled` | `Bool` | Channel enabled status |
| `lastMessageDate` | `Date?` | Most recent message date |
| `unreadCount` | `Int` | Unread messages |
| `unreadMentionCount` | `Int` | Unread @-mentions |
| `notificationLevel` | `NotificationLevel` | Per-channel notification preference |
| `isFavorite` | `Bool` | Favorite flag |
| `floodScopeModeRawValue` | `String` | Flood-scope mode (raw) |

### Computed Properties

| Property | Type | Description |
|----------|------|-------------|
| `isPublicChannel` | `Bool` | True if this is slot 0 (the public channel slot) |

---

## Additional Services

| Service | Type | Description |
|---------|------|-------------|
| `MessagePollingService` | internal, actor | Polls device for pending messages, routes to handlers |
| `SettingsService` | public, actor | Manages device settings (name, location, radio) |
| `AdvertisementService` | public, actor | Sends advertisements to mesh |
| `RoomServerService` | public, actor | Handles room server messaging |
| `RepeaterAdminService` | public, actor | Admin commands for repeater nodes |
| `BinaryProtocolService` | public, actor | Binary protocol encoding/decoding |
| `KeychainService` | internal, actor | Secure credential storage |
| `NotificationService` | public, @MainActor, @Observable class | Local notification scheduling |
| `ReactionService` | public, actor | Parses and persists emoji reactions |
| `RxLogService` | public, actor | Captures RF packets for network diagnostics |
| `PersistentLogger` | public, struct | Writes to OSLog and enqueues buffered debug log entries |
| `DebugLogBuffer` | public, actor | Batches debug logs and writes to SwiftData |
| `CommandAuditLogger` | internal, actor | Structured logging of remote-node operations (login, status, telemetry, CLI, keep-alive) for diagnostics |
| `DeviceService` | public, actor | Device update callback and OCV settings persistence |
| `HeardRepeatsService` | public, actor | Tracks channel-message repeat counts for propagation analysis |
| `ServiceContainer` | @MainActor, public final class | Holds all service instances |

---

## New Services

### RxLogService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/RxLogService.swift`

Captures and stores RF packet log entries for network diagnostics.

**Methods:**

| Method | Description |
|--------|-------------|
| `startEventMonitoring(radioID:)` | Starts capturing RF packets from transport events |
| `stopEventMonitoring()` | Stops packet capture |
| `process(_:) async` | Processes a parsed RX-log packet into a stored entry |
| `loadExistingEntries() async -> [RxLogEntryDTO]` | Loads stored log entries |
| `decryptEntry(_:) -> RxLogEntryDTO` | Decrypts a stored entry's payload |
| `clearEntries() async` | Clears all log entries |

**Capture Features:**
- Real-time packet monitoring
- Automatic metadata extraction (RSSI, SNR, packet type)
- SwiftData persistence

### PersistentLogger (public, struct)

**File:** `MC1Services/Sources/MC1Services/Services/PersistentLogger.swift`

Writes to OSLog and enqueues debug log entries into `DebugLogBuffer` for persistence.

**Methods:**

| Method | Description |
|--------|-------------|
| `debug(_:)` | Logs a debug message |
| `info(_:)` | Logs an info message |
| `notice(_:)` | Logs a notice message |
| `warning(_:)` | Logs a warning message |
| `error(_:)` | Logs an error message |
| `fault(_:)` | Logs a fault message |

### DebugLogBuffer (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/DebugLogBuffer.swift`

Buffered log sink that batches debug log entries and writes to SwiftData.

**Methods:**

| Method | Description |
|--------|-------------|
| `append(_:)` | Adds a debug log entry to the buffer |
| `flush()` | Flushes buffered entries to SwiftData |
| `shutdown()` | Cancels scheduled flush and persists remaining entries |

**Buffer Features:**
- Batched persistence (flush interval: 5 seconds or 50 entries)
- Thread-safe actor isolation

### CommandAuditLogger (internal, actor)

**File:** `MC1Services/Sources/MC1Services/Services/CommandAuditLogger.swift`

Structured `Logger`-based logging of remote-node operations for diagnostics. It logs events rather than persisting an auditable history; there is no `CommandAuditEntryDTO` and no query API.

**Methods (selection):**

| Method | Description |
|--------|-------------|
| `logLoginRequest(target:publicKey:pathLength:)` | Logs a remote-node login attempt |
| `logLoginSuccess(target:publicKey:isAdmin:)` | Logs a successful login |
| `logLoginFailed(target:publicKey:reason:)` | Logs a failed login |
| `logCLICommand(publicKey:command:)` | Logs a CLI command sent to a node |
| `logCLIResponse(publicKey:response:)` | Logs a CLI command response |
| `logStatusRequest(target:publicKey:)` / `logTelemetryRequest(target:publicKey:)` | Logs status/telemetry queries |
| `logKeepAlive(target:publicKey:)` | Logs a keep-alive |

### DeviceService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/DeviceService.swift`

Wires a device-update callback and persists OCV battery-curve settings. General device fetches go through `PersistenceStore` directly.

**Methods:**

| Method | Description |
|--------|-------------|
| `setDeviceUpdateCallback(_:)` | Registers a callback invoked when the device DTO changes |
| `updateOCVSettings(deviceID:preset:customArray:) async throws` | Persists the OCV preset and custom curve for a device |

### HeardRepeatsService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/HeardRepeatsService.swift`

Tracks message repeat counts for channel message propagation analysis.

**Methods:**

| Method | Description |
|--------|-------------|
| `configure(radioID:localNodeName:)` | Configures the service for the active radio |
| `events() -> AsyncStream<HeardRepeatEvent>` | Stream of heard-repeat events for UI updates |
| `processForRepeats(_:) async -> Int?` | Counts a repeat from a parsed RX-log entry, returning the new count |
| `refreshRepeats(for:) async -> [MessageRepeatDTO]` | Returns the recorded repeats for a message |

**Repeats Features:**
- Real-time tracking of message propagation
- Stored with Message model
- Used for displaying "Heard by X" in channel messages

Note: `ElevationService` and `LocationService` are app-layer utilities in MeshCore One (not part of the MC1Services package). See `docs/api/MeshCore One.md` for app-layer references.

---

## New Models

### RxLogEntryDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/RxLogEntry.swift`

A sendable snapshot of RF packet log entry.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `radioID` | `UUID` | Associated device (data-partition key) |
| `receivedAt` | `Date` | When packet was received |
| `snr` | `Double?` | Signal-to-noise ratio in dB |
| `rssi` | `Int?` | Received signal strength indicator in dBm |
| `routeType` | `RouteType` | Route type (flood vs. direct) |
| `payloadType` | `PayloadType` | Decoded packet payload type |
| `pathLength` | `UInt8` | Routing path length |
| `pathNodes` | `Data` | Routing path bytes |
| `packetPayload` | `Data` | Decoded payload bytes |
| `rawPayload` | `Data` | Raw on-air payload bytes |
| `packetHash` | `String` | Packet hash |
| `decryptStatus` | `DecryptStatus` | Whether the payload decrypted |

Sender/recipient key prefixes are exposed as the computed `senderPrefix` / `recipientPrefix` properties.

### DebugLogEntryDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/DebugLogEntry.swift`

A sendable snapshot of debug log entry.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `timestamp` | `Date` | When log was created |
| `level` | `DebugLogLevel` | Severity: `.debug`, `.info`, `.notice`, `.warning`, `.error`, `.fault` |
| `subsystem` | `String` | Logging subsystem identifier |
| `category` | `String` | Logging category |
| `message` | `String` | Log message |

### DiscoveredNodeDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/DiscoveredNode.swift`

A sendable snapshot of a discovered node (advertisement cache for Discovery).

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `radioID` | `UUID` | Associated device (data-partition key) |
| `publicKey` | `Data` | 32-byte public key |
| `name` | `String` | Advertised node name |
| `typeRawValue` | `UInt8` | Node type raw value |
| `lastHeard` | `Date` | Last advertisement timestamp (local) |
| `lastAdvertTimestamp` | `UInt32` | Firmware advertisement timestamp |
| `latitude` | `Double` | Node latitude |
| `longitude` | `Double` | Node longitude |
| `outPathLength` | `UInt8` | Routing path length |
| `outPath` | `Data` | Routing path data |

### ReactionDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/Reaction.swift`

A sendable snapshot of a reaction on a message.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `messageID` | `UUID` | Target message ID |
| `emoji` | `String` | Reaction emoji |
| `senderName` | `String` | Sender display name |
| `messageHash` | `String` | Reaction hash (Crockford Base32) |
| `rawText` | `String` | Raw wire-format text |
| `receivedAt` | `Date` | Received timestamp |
| `channelIndex` | `UInt8?` | Channel index (nil for DM) |
| `contactID` | `UUID?` | Contact ID (DM only) |
| `radioID` | `UUID` | Associated device (data-partition key) |

### ElevationSample (public, struct)

**File:** `MC1Services/Sources/MC1Services/RF/ElevationSample.swift`

Represents a terrain elevation data point.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `coordinate` | `CLLocationCoordinate2D` | Geographic coordinates |
| `elevation` | `Double` | Elevation in meters above sea level |
| `distanceFromAMeters` | `Double` | Distance from point A in meters |

### OCVPreset (public, enum)

**File:** `MC1Services/Sources/MC1Services/Models/OCVPreset.swift`

`String`-raw-valued, `CaseIterable`, `Codable`, `Sendable` enum of built-in Open Circuit Voltage (OCV) battery discharge curve presets. It is an enum, not a DTO struct.

---

## See Also

- [Architecture Overview](../Architecture.md)
- [Sync Guide](../guides/Sync.md)
- [Messaging Guide](../guides/Messaging.md)
