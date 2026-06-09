# MC1Services API Reference

The `MC1Services` layer provides actor-isolated business logic, managing services, persistence, and device connections.

## Package Information

- **Location:** `MC1Services/`
- **Type:** Swift Package (single library target)
- **Dependencies:** MeshCore

---

## ConnectionManager (public, @MainActor, @Observable class)

**File:** `MC1Services/Sources/MC1Services/ConnectionManager.swift`

The primary entry point for managing the connection to a MeshCore device and coordinating services.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `connectionState` | `ConnectionState` | Current state: `.disconnected`, `.connecting`, `.connected`, `.ready` |
| `connectedDevice` | `DeviceDTO?` | Currently connected device info |
| `services` | `ServiceContainer?` | Business logic services (available when `.ready`) |
| `currentTransportType` | `TransportType?` | Active transport type (`.bluetooth` or `.wifi`) |

### Methods

| Method | Description |
|--------|-------------|
| `activate() async` | Initializes and attempts auto-reconnect to last device |
| `pairNewDevice() async throws` | Starts AccessorySetupKit pairing flow |
| `connect(to:) async throws` | Connects to a previously paired device |
| `disconnect() async` | Gracefully disconnects and stops services |
| `forgetDevice() async throws` | Removes device from app and system pairings |
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

**File:** `MC1Services/Sources/MC1Services/SyncCoordinator.swift`

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
public enum SyncState: Sendable, Equatable {
    case idle
    case syncing(progress: SyncProgress)
    case synced
    case failed(SyncCoordinatorError)
}
```

### Key Methods

| Method | Description |
|--------|-------------|
| `performFullSync(services:deviceID:) async throws` | Executes contacts → channels → messages sync |
| `onConnectionEstablished(deviceID:services:) async throws` | Called after BLE connection; wires handlers and syncs |
| `setSyncActivityCallbacks(onStarted:onEnded:)` | Sets UI callbacks for sync pill display |

### Connection Lifecycle

1. Wire message handlers (before events arrive)
2. Start event monitoring
3. Perform full sync (contacts, channels, messages)
4. Wire discovery handlers (for ongoing contact discovery)

---

## MessageService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/MessageService.swift`

Handles message sending with automatic retry logic, flood routing fallback, and ACK tracking.

### Configuration

```swift
public struct MessageServiceConfig: Sendable {
    let maxAttempts: Int           // Total attempts (default: 4)
    let floodAfter: Int            // Switch to flood after N (default: 2)
    let maxFloodAttempts: Int      // Max flood attempts (default: 2)
    let minTimeout: TimeInterval   // Minimum timeout seconds
    let floodFallbackOnRetry: Bool // Use flood on manual retry
}
```

### Messaging Methods

| Method | Description |
|--------|-------------|
| `sendMessageWithRetry(text:to:...) async throws -> MessageDTO` | Sends with auto-retry and flood fallback |
| `sendDirectMessage(text:to:...) async throws -> MessageDTO` | Single attempt send |
| `sendChannelMessage(text:channelIndex:...) async throws -> UUID` | Broadcasts to channel |
| `retryDirectMessage(messageID:to:) async throws -> MessageDTO` | Manual retry of failed message |

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

### Handlers

| Method | Description |
|--------|-------------|
| `setContactService(_:)` | Sets the contact service for path management during retry |
| `setAckConfirmationHandler(_:)` | Sets callback invoked when an ACK is received |
| `setMessageFailedHandler(_:)` | Sets callback invoked when a message fails after all retries |
| `setRetryStatusHandler(_:)` | Sets callback invoked during retry attempts |
| `setRoutingChangedHandler(_:)` | Sets callback invoked when routing mode changes during retry |

### Retry Flow

1. Attempts 1-2: Direct routing (using contact's outbound path)
2. Attempts 3-4: Flood routing (broadcast to all nearby nodes)
3. Returns immediately when ACK received
4. Marks failed if all attempts exhausted

---

## ContactService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/ContactService.swift`

Manages discovery, synchronization, and storage of mesh contacts.

### Sync Methods

| Method | Description |
|--------|-------------|
| `syncContacts(deviceID:since:) async throws -> ContactSyncResult` | Incremental or full contact sync |
| `setSyncCoordinator(_:)` | Set the sync coordinator for UI refresh notifications |
| `setSyncProgressHandler(_:)` | Set progress handler for sync operations |

### Contact Management

| Method | Description |
|--------|-------------|
| `getContact(deviceID:publicKey:) async throws -> ContactDTO?` | Get a specific contact by public key from local database |
| `addOrUpdateContact(deviceID:contact:) async throws` | Adds/updates contact on device and local store |
| `removeContact(deviceID:publicKey:) async throws` | Deletes from device and local store |

### Path Discovery & Routing

| Method | Description |
|--------|-------------|
| `sendPathDiscovery(deviceID:publicKey:) async throws -> MessageSentInfo` | Initiates route discovery |
| `resetPath(deviceID:publicKey:) async throws` | Resets routing, forces mesh rediscovery |
| `setPath(deviceID:publicKey:path:pathLength:) async throws` | Set a specific path for a contact |

### Contact Sharing

| Method | Description |
|--------|-------------|
| `shareContact(publicKey:) async throws` | Share a contact via zero-hop broadcast |
| `exportContact(publicKey:) async throws -> String` | Export a contact to a shareable URI (deprecated) |
| `exportContactURI(name:publicKey:type:) -> String` | Build a shareable contact URI (static method) |
| `importContact(cardData:) async throws` | Import a contact from card data |

### Local Database Operations

| Method | Description |
|--------|-------------|
| `getContacts(deviceID:) async throws -> [ContactDTO]` | Get all contacts for a device from local database |
| `getConversations(deviceID:) async throws -> [ContactDTO]` | Get conversations (contacts with messages) from local database |
| `getContactByID(_:) async throws -> ContactDTO?` | Get a contact by ID from local database |
| `updateContactPreferences(contactID:nickname:isBlocked:isFavorite:) async throws` | Update local contact preferences |
| `getDiscoveredContacts(deviceID:) async throws -> [ContactDTO]` | Get discovered contacts (from NEW_ADVERT push, not yet added to device) |
| `confirmContact(id:) async throws` | Confirm a discovered contact (mark as added to device) |

---

## ChannelService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/ChannelService.swift`

Manages group messaging channels and secure slot configuration.

### Sync Methods

| Method | Description |
|--------|-------------|
| `syncChannels(deviceID:maxChannels:) async throws -> ChannelSyncResult` | Syncs all channel slot configurations |

### Channel CRUD Operations

| Method | Description |
|--------|-------------|
| `fetchChannel(index:) async throws -> ChannelInfo?` | Fetches a single channel from the device |
| `setChannel(deviceID:index:name:passphrase:) async throws` | Configures slot with passphrase (SHA-256 hashed) |
| `setChannelWithSecret(deviceID:index:name:secret:) async throws` | Sets a channel with a pre-computed secret |
| `clearChannel(deviceID:index:) async throws` | Resets a channel slot |

### Local Database Operations

| Method | Description |
|--------|-------------|
| `getChannels(deviceID:) async throws -> [ChannelDTO]` | Gets all channels from local database for a device |
| `getChannel(deviceID:index:) async throws -> ChannelDTO?` | Gets a specific channel from local database |
| `getActiveChannels(deviceID:) async throws -> [ChannelDTO]` | Gets channels that have messages (for chat list) |
| `setChannelEnabled(channelID:isEnabled:) async throws` | Updates a channel's enabled state locally |
| `clearUnreadCount(channelID:) async throws` | Clears unread count for a channel |

### Public Channel (Slot 0)

| Method | Description |
|--------|-------------|
| `setupPublicChannel(deviceID:) async throws` | Initializes default public channel on slot 0 |
| `hasPublicChannel(deviceID:) async throws -> Bool` | Checks if the public channel exists locally |

### Handlers

| Method | Description |
|--------|-------------|
| `setChannelUpdateHandler(_:)` | Sets a callback for channel updates |

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
| `createSession(deviceID:contact:password:rememberPassword:) async throws -> RemoteNodeSessionDTO` | Create a new session for a remote node |
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
| `requestHistorySync(sessionID:since:) async throws` | Request message history from a room server |

### CLI Commands

| Method | Description |
|--------|-------------|
| `sendCLICommand(sessionID:command:) async throws -> String` | Send a CLI command to a remote node (admin only) |

### Connection Management

| Method | Description |
|--------|-------------|
| `disconnect(sessionID:) async` | Mark session as disconnected without sending logout |
| `handleBLEReconnection() async` | Called when BLE connection is re-established |
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
| `fetchContacts(deviceID:) throws -> [ContactDTO]` | Fetch all contacts for a device |
| `fetchContact(id:) throws -> ContactDTO?` | Fetch a contact by ID |
| `fetchContact(deviceID:publicKey:) throws -> ContactDTO?` | Fetch a contact by public key |
| `fetchConversations(deviceID:) throws -> [ContactDTO]` | Fetch contacts with messages |
| `fetchDiscoveredContacts(deviceID:) throws -> [ContactDTO]` | Fetch discovered contacts not yet added to device |
| `saveContact(_:) throws` | Save or update a contact |
| `saveContact(deviceID:from:) throws -> UUID` | Save contact from ContactFrame |
| `deleteContact(id:) throws` | Delete a contact |
| `updateContactLastMessage(contactID:date:) throws` | Update contact's last message date |
| `confirmContact(id:) throws` | Mark discovered contact as confirmed |

### Message Operations

| Method | Description |
|--------|-------------|
| `fetchMessages(contactID:) throws -> [MessageDTO]` | Fetch all messages for a contact |
| `fetchMessages(deviceID:channelIndex:) throws -> [MessageDTO]` | Fetch all messages for a channel |
| `fetchMessage(id:) throws -> MessageDTO?` | Fetch a message by ID |
| `saveMessage(_:) throws` | Save or update a message |
| `deleteMessage(id:) throws` | Delete a message |
| `updateMessageStatus(id:status:) throws` | Update message delivery status |
| `updateMessageAck(id:ackCode:status:roundTripTime:) throws` | Update message ACK code, status, and round-trip time |
| `updateMessageRetryStatus(id:status:retryAttempt:maxRetryAttempts:) throws` | Update message retry status |
| `updateMessageHeardRepeats(id:heardRepeats:) throws` | Update message heard repeats count |
| `markMessagesAsRead(contactID:) throws` | Mark all messages as read for a contact |

### Channel Operations

| Method | Description |
|--------|-------------|
| `fetchChannels(deviceID:) throws -> [ChannelDTO]` | Fetch all channels for a device |
| `fetchChannel(id:) throws -> ChannelDTO?` | Fetch a channel by ID |
| `fetchChannel(deviceID:index:) throws -> ChannelDTO?` | Fetch a channel by index |
| `saveChannel(_:) throws` | Save or update a channel |
| `saveChannel(deviceID:from:) throws -> UUID` | Save channel from ChannelInfo |
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

**File:** `MC1Services/Sources/MC1Services/Models/Device.swift:152`

A sendable snapshot of Device for cross-actor transfers. Total: 27 properties.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Device identifier |
| `publicKey` | `Data` | Device public key |
| `nodeName` | `String` | Device node name |
| `firmwareVersion` | `UInt8` | Firmware version number |
| `firmwareVersionString` | `String` | Firmware version string |
| `manufacturerName` | `String` | Manufacturer name |
| `buildDate` | `String` | Firmware build date |
| `maxContacts` | `UInt8` | Maximum contacts supported |
| `maxChannels` | `UInt8` | Maximum channels supported |
| `frequency` | `UInt32` | Radio frequency (kHz) |
| `bandwidth` | `UInt32` | Radio bandwidth (Hz) |
| `spreadingFactor` | `UInt8` | LoRa spreading factor |
| `codingRate` | `UInt8` | LoRa coding rate |
| `txPower` | `UInt8` | Transmit power (dBm) |
| `maxTxPower` | `UInt8` | Maximum transmit power (dBm) |
| `latitude` | `Double` | Device location latitude |
| `longitude` | `Double` | Device location longitude |
| `blePin` | `UInt32` | BLE pairing PIN |
| `manualAddContacts` | `Bool` | Manual contact add mode |
| `multiAcks` | `Bool` | Multiple ACKs enabled |
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

**File:** `MC1Services/Sources/MC1Services/Models/Channel.swift:92`

A sendable snapshot of Channel for cross-actor transfers. Total: 8 properties.

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Local identifier |
| `deviceID` | `UUID` | Associated device |
| `index` | `UInt8` | Slot number (0-7) |
| `name` | `String` | Channel name |
| `secret` | `Data` | Channel encryption secret (16 bytes) |
| `isEnabled` | `Bool` | Channel enabled status |
| `lastMessageDate` | `Date?` | Most recent message date |
| `unreadCount` | `Int` | Unread messages |

### Computed Properties

| Property | Type | Description |
|----------|------|-------------|
| `isPublicChannel` | `Bool` | True if this is slot 0 (the public channel slot) |

### ChatFilterDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/ChatFilter.swift`

User preferences for filtering chat/conversation lists.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `filterUnreadOnly` | `Bool` | Show only conversations with unread messages |
| `filterDirectMessagesOnly` | `Bool` | Show only direct message conversations (hide channels) |
| `filterMutedConversations` | `Bool` | Exclude muted conversations |
| `filterBlockedContacts` | `Bool` | Exclude conversations with blocked contacts |

---

## Additional Services

| Service | Type | Description |
|---------|------|-------------|
| `MessagePollingService` | public, actor | Polls device for pending messages, routes to handlers |
| `SettingsService` | public, actor | Manages device settings (name, location, radio) |
| `AdvertisementService` | public, actor | Sends advertisements to mesh |
| `RoomServerService` | public, actor | Handles room server messaging |
| `RepeaterAdminService` | public, actor | Admin commands for repeater nodes |
| `BinaryProtocolService` | public, actor | Binary protocol encoding/decoding |
| `KeychainService` | public, actor | Secure credential storage |
| `NotificationService` | public, @MainActor, @Observable class | Local notification scheduling |
| `ReactionService` | public, actor | Parses and persists emoji reactions |
| `RxLogService` | public, actor | Captures RF packets for network diagnostics |
| `PersistentLogger` | public, struct | Writes to OSLog and enqueues buffered debug log entries |
| `DebugLogBuffer` | public, actor | Batches debug logs and writes to SwiftData |
| `CommandAuditLogger` | public, actor | Audits CLI commands sent to devices for security and debugging |
| `DeviceService` | public, actor | Provides device information and management operations |
| `HeardRepeatsService` | public, actor | Tracks message repeat counts for channel message propagation |
| `ServiceContainer` | public, class | Holds all service instances |

---

## New Services

### RxLogService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/RxLogService.swift`

Captures and stores RF packet log entries for network diagnostics.

**Methods:**

| Method | Description |
|--------|-------------|
| `startCapture() async` | Starts capturing RF packets from transport |
| `stopCapture() async` | Stops packet capture |
| `getRecentLogs(limit:) async throws -> [RxLogEntryDTO]` | Gets recent log entries |
| `clearLogs() async throws` | Clears all log entries |
| `getFilteredLogs(type:source:destination:limit:) async throws -> [RxLogEntryDTO]` | Gets logs with optional filters |

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

### CommandAuditLogger (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/CommandAuditLogger.swift`

Audits CLI commands sent to devices for security and debugging.

**Methods:**

| Method | Description |
|--------|-------------|
| `logCommand(deviceID:command:response:) async` | Logs a CLI command and its response |
| `getAuditedCommands(deviceID:since:limit:) async throws -> [CommandAuditEntryDTO]` | Gets audited commands with filters |
| `clearOldCommands(olderThan:) async throws` | Deletes commands older than specified date |

**Audit Features:**
- SwiftData persistence for command history
- Command/response correlation tracking
- Timestamp recording for troubleshooting
- Configurable retention (default: 30 days)

### DeviceService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/DeviceService.swift`

Provides device information and management operations.

**Methods:**

| Method | Description |
|--------|-------------|
| `getDevice(deviceID:) async throws -> DeviceDTO?` | Gets device by ID |
| `getActiveDevice() async throws -> DeviceDTO?` | Gets currently active device |
| `getDevices() async throws -> [DeviceDTO]` | Gets all devices |
| `updateDeviceLastSeen(deviceID:) async throws` | Updates device's last seen timestamp |
| `getActiveDeviceTransportType() async throws -> TransportType?` | Gets transport type (BLE or WiFi) |

### HeardRepeatsService (public, actor)

**File:** `MC1Services/Sources/MC1Services/Services/HeardRepeatsService.swift`

Tracks message repeat counts for channel message propagation analysis.

**Methods:**

| Method | Description |
|--------|-------------|
| `incrementRepeats(messageID:) async throws` | Increments heard repeats count for a message |
| `getRepeats(messageID:) async throws -> Int?` | Gets heard repeats count for a message |
| `resetRepeats(messageID:) async throws` | Resets repeats count to zero |

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
| `deviceID` | `UUID` | Associated device |
| `timestamp` | `Date` | When packet was received |
| `sourcePrefix` | `Data` | First 6 bytes of sender's public key |
| `destinationPrefix` | `Data` | First 6 bytes of destination's public key |
| `packetType` | `String` | Type of packet (message, control, telemetry, status, ACK, NACK) |
| `rssi` | `Int?` | Received signal strength indicator in dBm |
| `snr` | `Double?` | Signal-to-noise ratio in dB |
| `payload` | `Data?` | Packet payload (if readable) |

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
| `deviceID` | `UUID` | Associated device |
| `publicKey` | `Data` | 32-byte public key |
| `name` | `String` | Advertised node name |
| `typeRawValue` | `UInt8` | Node type raw value |
| `lastHeard` | `Date` | Last advertisement timestamp (local) |
| `lastAdvertTimestamp` | `UInt32` | Firmware advertisement timestamp |
| `latitude` | `Double` | Node latitude |
| `longitude` | `Double` | Node longitude |
| `outPathLength` | `Int8` | Routing path length (-1 = flood) |
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
| `deviceID` | `UUID` | Associated device |

### CommandAuditEntryDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/CommandAuditEntry.swift`

A sendable snapshot of a CLI command audit entry.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `timestamp` | `Date` | When command was sent |
| `command` | `String` | CLI command that was sent |
| `response` | `String?` | Command response from device |
| `targetNodePrefix` | `Data?` | First 6 bytes of target node's public key |
| `success` | `Bool` | Whether command succeeded |

### ElevationSample (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/ElevationSample.swift`

Represents a terrain elevation data point.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `coordinate` | `CLLocationCoordinate2D` | Geographic coordinates |
| `elevation` | `Double` | Elevation in meters above sea level |
| `distanceFromAMeters` | `Double` | Distance from point A in meters |

### OCVPresetDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/OCVPreset.swift`

Represents an Open Circuit Voltage (OCV) battery discharge curve preset.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `name` | `String` | Preset name (e.g., "LiFePO4", "Li-Ion NMC") |
| `manufacturer` | `String` | Battery manufacturer |
| `curveData` | `[Double]` | OCV curve points (voltage, percentage) |

### NotificationPreferencesDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/NotificationPreferences.swift`

User notification preferences.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `enabled` | `Bool` | Whether notifications are enabled |
| `soundEnabled` | `Bool` | Whether sound is enabled |
| `vibrateEnabled` | `Bool` | Whether vibration is enabled |

### TrustedContactsDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/TrustedContacts.swift`

List of trusted contacts for enhanced security features.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `trustedContactIDs` | `[UUID]` | List of trusted contact IDs |

### LinkPreviewPreferencesDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/LinkPreviewPreferences.swift`

User preferences for link preview behavior.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `enabled` | `Bool` | Whether link previews are enabled |
| `alwaysFetchPreviews` | `Bool` | Fetch previews even when using WiFi/cellular (for testing) |

### ChatFilterDTO (public, struct)

**File:** `MC1Services/Sources/MC1Services/Models/ChatFilter.swift`

User preferences for filtering chat/conversation lists.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier |
| `deviceID` | `UUID` | Associated device |
| `filterUnreadOnly` | `Bool` | Show only conversations with unread messages |
| `filterDirectMessagesOnly` | `Bool` | Show only direct message conversations (hide channels) |
| `filterMutedConversations` | `Bool` | Exclude muted conversations |
| `filterBlockedContacts` | `Bool` | Exclude conversations with blocked contacts |

---

## See Also

- [Architecture Overview](../Architecture.md)
- [Sync Guide](../guides/Sync.md)
- [Messaging Guide](../guides/Messaging.md)
