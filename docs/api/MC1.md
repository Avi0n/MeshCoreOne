# MeshCore One App API Reference

The `MeshCore One` app layer manages the user interface, application lifecycle, and coordinates services.

## Target Information

- **Location:** `MC1/`
- **Type:** iOS Application
- **Dependencies:** MC1Services, MeshCore

---

## AppState (public, @MainActor, @Observable class)

**File:** `MC1/AppState.swift`

The central state management object for the application.

### Connection Properties

| Property | Type | Description |
|----------|------|-------------|
| `connectionManager` | `ConnectionManager` | Source of truth for device connection |
| `connectionState` | `ConnectionState` | Convenience accessor for connection status |
| `services` | `ServiceContainer?` | Business logic services (when connected) |
| `syncCoordinator` | `SyncCoordinator?` | Coordinates background sync operations |
| `connectedDevice` | `DeviceDTO?` | Currently connected device information |
| `deviceBatteryMillivolts` | `UInt16?` | Device battery voltage in millivolts |

### Navigation State

| Property | Type | Description |
|----------|------|-------------|
| `selectedTab` | `Int` | Active tab: 0=Chats, 1=Nodes, 2=Map, 3=Tools, 4=Settings |
| `hasCompletedOnboarding` | `Bool` | Whether onboarding flow is complete |
| `tabBarVisibility` | `Visibility` | Controls tab bar visibility (e.g., hidden in chat) |
| `pendingChatContact` | `ContactDTO?` | Contact to navigate to after connection |
| `pendingRoomSession` | `RemoteNodeSessionDTO?` | Room session to navigate to after connection |
| `onboardingStep` | `OnboardingStep` | Current step in onboarding flow |

### Navigation Methods

| Method | Description |
|--------|-------------|
| `navigateToChat(with:)` | Triggers navigation to a specific chat conversation |
| `navigateToDiscovery()` | Triggers navigation to contact discovery screen |
| `navigateToRoom(with:)` | Triggers navigation to a room server session |
| `navigateToContacts()` | Switches to Contacts tab |
| `clearPendingNavigation()` | Clears pending navigation state |

### Lifecycle Methods

| Method | Description |
|--------|-------------|
| `initialize() async` | Call on launch to activate services and auto-reconnect |
| `handleReturnToForeground() async` | Updates unread counts and checks expired ACKs |
| `handleEnterBackground()` | Handles app entering background state |
| `startDeviceScan()` | Initiates Bluetooth device scanning |
| `disconnect()` | Disconnects from current device |
| `fetchDeviceBattery()` | Fetches current device battery level |
| `completeOnboarding()` | Marks onboarding as complete |
| `resetOnboarding()` | Resets onboarding state to welcome screen |

### UI Coordination

| Property | Type | Description |
|----------|------|-------------|
| `messageEventBroadcaster` | `MessageEventBroadcaster` | Triggers UI refreshes for service events |
| `shouldShowSyncingPill` | `Bool` | Indicates background sync in progress |
| `servicesVersion` | `Int` | Incremented to trigger view reloads when services change |
| `contactsVersion` | `Int` | Incremented to trigger contact list updates |
| `conversationsVersion` | `Int` | Incremented to trigger conversation list updates |

---

## MessageEventBroadcaster (public, @MainActor, @Observable class)

**File:** `MC1/Services/MessageEventBroadcaster.swift`

Bridges service layer callbacks to SwiftUI's `@MainActor` context for real-time UI updates.

### Event Types

```swift
public enum MessageEvent: Sendable, Equatable {
    case directMessageReceived(message: MessageDTO, contact: ContactDTO)
    case channelMessageReceived(message: MessageDTO, channelIndex: UInt8)
    case roomMessageReceived(message: RoomMessageDTO, sessionID: UUID)
    case messageStatusUpdated(ackCode: UInt32)
    case messageFailed(messageID: UUID)
    case messageRetrying(messageID: UUID, attempt: Int, maxAttempts: Int)
    case heardRepeatRecorded(messageID: UUID, count: Int)
    case reactionReceived(messageID: UUID, summary: String)
    case routingChanged(contactID: UUID, isFlood: Bool)
    case roomMessageStatusUpdated(messageID: UUID)
    case roomMessageFailed(messageID: UUID)
    case unknownSender(keyPrefix: Data)
    case error(String)
}
```

### Observable Properties

| Property | Type | Description |
|----------|------|-------------|
| `latestMessage` | `MessageDTO?` | Latest received message |
| `latestEvent` | `MessageEvent?` | Latest event for reactive updates |
| `newMessageCount` | `Int` | Incremented to trigger view updates |

### Event Handlers

| Method | Description |
|--------|-------------|
| `handleDirectMessage(_:from:)` | Handles incoming direct message |
| `handleChannelMessage(_:channelIndex:)` | Handles incoming channel message |
| `handleRoomMessage(_:contact:)` | Handles incoming room message |
| `handleAcknowledgement(ackCode:)` | Handles ACK receipt |
| `handleMessageFailed(messageID:)` | Handles delivery failure |
| `handleMessageRetrying(messageID:attempt:maxAttempts:)` | Handles retry progress |
| `handleHeardRepeatRecorded(messageID:count:)` | Handles heard-repeat updates |
| `handleReactionReceived(messageID:summary:)` | Handles reaction summary updates |
| `handleRoutingChanged(contactID:isFlood:)` | Handles routing mode change |
| `handleUnknownSender(keyPrefix:)` | Handles message from unknown sender |
| `handleError(_:)` | Handles error events |
| `handleRoomMessageStatusUpdated(messageID:)` | Handles room message status updates |
| `handleRoomMessageFailed(messageID:)` | Handles room message failures |

---

## ViewModels

### ChatViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Chats/ChatViewModel.swift`

Manages state for the chat conversation view.

| Property | Type | Description |
|----------|------|-------------|
| `messages` | `[MessageDTO]` | Conversation messages |
| `currentContact` | `ContactDTO?` | Current chat contact |
| `currentChannel` | `ChannelDTO?` | Current channel being viewed |
| `conversations` | `[ContactDTO]` | Current conversations (contacts with messages) |
| `channels` | `[ChannelDTO]` | Current channels with messages |
| `roomSessions` | `[RemoteNodeSessionDTO]` | Current room sessions |
| `isLoading` | `Bool` | Loading state |
| `isSending` | `Bool` | Whether a message is being sent |
| `composingText` | `String` | Message text being composed |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadMessages(for:)` | Load messages for a contact |
| `sendMessage()` | Send message to current contact |
| `retryMessage(_:)` | Retry failed message with flood routing |
| `loadChannelMessages(for:)` | Load messages for a channel |
| `sendChannelMessage()` | Send message to current channel |

### ContactsViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Contacts/ContactsViewModel.swift`

Manages state for the contacts list view.

| Property | Type | Description |
|----------|------|-------------|
| `contacts` | `[ContactDTO]` | All contacts |
| `isLoading` | `Bool` | Loading state |
| `isSyncing` | `Bool` | Syncing state |
| `syncProgress` | `(Int, Int)?` | Sync progress (current, total) |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadContacts(deviceID:)` | Load contacts from local database |
| `syncContacts(deviceID:)` | Sync contacts from device |
| `filteredContacts(searchText:showFavoritesOnly:)` | Returns filtered and sorted contacts |
| `toggleFavorite(contact:)` | Toggle favorite status |
| `toggleBlocked(contact:)` | Toggle blocked status |

### MapViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Map/MapViewModel.swift`

Manages state for the map view showing contact locations.

| Property | Type | Description |
|----------|------|-------------|
| `contactsWithLocation` | `[ContactDTO]` | Contacts with valid coordinates |
| `selectedContact` | `ContactDTO?` | Currently selected marker |
| `cameraPosition` | `MapCameraPosition` | Map viewport position |
| `isLoading` | `Bool` | Loading state |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadContactsWithLocation()` | Load contacts with valid locations |
| `centerOnContact(_:)` | Center map on a specific contact |
| `centerOnAllContacts()` | Center map to show all contacts |

---

### Diagnostic ViewModels

### LineOfSightViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/LineOfSight/LineOfSightViewModel.swift`

Manages state and calculations for RF line of sight analysis.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `fromContact` | `ContactDTO?` | Starting point of analysis |
| `fromCoordinates` | `CLLocationCoordinate2D?` | Manual coordinates as starting point |
| `toContact` | `ContactDTO?` | Target contact for analysis |
| `toCoordinates` | `CLLocationCoordinate2D?` | Manual coordinates as target point |
| `elevationSamples` | `[ElevationSample]` | Terrain elevation samples along path |
| `rfParameters` | `RFParameters` | RF calculation parameters (frequency, antenna height) |
| `terrainClearance` | `TerrainClearance?` | Analysis results (clearance percentage, Fresnel zones) |
| `isAnalyzing` | `Bool` | Whether analysis is in progress |
| `error` | `String?` | Error message if analysis failed |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `analyzeLineOfSight() async` | Performs complete line of sight analysis |
| `clearResults()` | Clears analysis results |
| `savePathAsFavorite() async` | Saves analyzed path for quick access |

### TracePathViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Contacts/TracePathViewModel.swift`

Manages manual path construction, path tracing, and saved path management for network routing.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `fromContact` | `ContactDTO?` | Source contact for path discovery |
| `savedPaths` | `[SavedPathDTO]` | All saved routing paths |
| `discoveryResult` | `PathDiscoveryResult?` | Current path discovery result |
| `editingPath` | `SavedPathDTO?` | Path currently being edited |
| `isDiscovering` | `Bool` | Path discovery in progress |
| `showSavedPathsSheet` | `Bool` | Show saved paths sheet |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `buildPath(from:to:) async` | Builds a path from source to target using available repeaters |
| `tracePath(path:) async` | Traces a manually-built path through the network |
| `savePath(name:path:) async` | Saves a path to persistent storage |
| `deletePath(id:) async` | Deletes a saved path |
| `editPath(id:name:path:) async` | Updates an existing saved path |

**Note**: The Trace Path tool uses manual path construction where users select and order repeaters. Automatic path discovery (e.g., breadth-first search) is not currently implemented.

### RxLogViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Tools/RxLogViewModel.swift`

Manages RF packet capture and log display for network diagnostics.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `logEntries` | `[RxLogEntryDTO]` | Captured RF packet log entries |
| `isCapturing` | `Bool` | Whether packet capture is active |
| `filterType` | `String?` | Filter by packet type |
| `filterSource` | `Data?` | Filter by source public key prefix |
| `filterDestination` | `Data?` | Filter by destination public key prefix |
| `signalThreshold` | `Int?` | Filter by signal strength (RSSI) |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `startCapture() async` | Starts packet capture from transport |
| `stopCapture() async` | Stops packet capture |
| `clearLogs() async` | Clears all captured logs |
| `exportLogs(timeRange:) async` | Exports logs to structured JSON |
| `applyFilters()` | Applies current filters to log entries |

---

## Data Models

### Conversation (enum)

**File:** `MC1/Models/Conversation.swift`

Represents different types of conversations in the app. Provides a unified interface for displaying direct chats, channels, and room sessions in the conversation list.

```swift
enum Conversation: Identifiable, Hashable {
    case direct(ContactDTO)
    case channel(ChannelDTO)
    case room(RemoteNodeSessionDTO)

    var id: UUID {
        switch self {
        case .direct(let contact): contact.id
        case .channel(let channel): channel.id
        case .room(let session): session.id
        }
    }
}
```

**Cases:**

| Case | Description |
|------|-------------|
| `direct(ContactDTO)` | One-on-one conversation with a contact |
| `channel(ChannelDTO)` | Group conversation on a mesh channel |
| `room(RemoteNodeSessionDTO)` | Multi-user room server session |

**Computed Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier for the conversation |
| `displayName` | `String` | Display name for the conversation |
| `lastMessageDate` | `Date?` | Timestamp of last message or activity |
| `unreadCount` | `Int` | Number of unread messages |
| `isChannel` | `Bool` | Whether this is a channel conversation |
| `isRoom` | `Bool` | Whether this is a room conversation |
| `channelIndex` | `UInt8?` | Channel index if channel, nil otherwise |
| `contact` | `ContactDTO?` | Contact if direct chat, nil otherwise |
| `channel` | `ChannelDTO?` | Channel if channel chat, nil otherwise |
| `roomSession` | `RemoteNodeSessionDTO?` | Room session if room chat, nil otherwise |

### OnboardingStep (enum)

**File:** `MC1/AppState.swift`

Represents the steps in the onboarding flow.

```swift
enum OnboardingStep: Int, CaseIterable {
    case welcome
    case permissions
    case deviceScan
    case radioPreset
}
```

**Cases:**

| Case | Description |
|------|-------------|
| `welcome` | Initial welcome screen |
| `permissions` | Bluetooth and notification permissions request |
| `deviceScan` | Device scanning and connection |
| `radioPreset` | Radio preset selection for companion devices |

**Computed Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `next` | `OnboardingStep?` | Next step in flow (nil if last) |
| `previous` | `OnboardingStep?` | Previous step in flow (nil if first) |

### PathHop (struct)

**File:** `MC1/Views/Contacts/PathManagementViewModel.swift`

Represents a single hop in a routing path with stable identity for SwiftUI.

```swift
struct PathHop: Identifiable, Equatable {
    let id: UUID
    var hashByte: UInt8
    var resolvedName: String?
}
```

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier for SwiftUI list management |
| `hashByte` | `UInt8` | First byte of repeater's public key |
| `resolvedName` | `String?` | Contact name if resolved, nil if unknown |
| `displayText` | `String` | Formatted display text (name + hash or just hash) |

### PathDiscoveryResult (enum)

**File:** `MC1/Views/Contacts/PathManagementViewModel.swift`

Result of a path discovery operation.

```swift
enum PathDiscoveryResult: Equatable {
    case success(hopCount: Int, fromCache: Bool = false)
    case noPathFound
    case failed(String)
}
```

**Cases:**

| Case | Description |
|------|-------------|
| `success(hopCount:fromCache:)` | Path discovered successfully with hop count |
| `noPathFound` | Remote node did not respond to discovery |
| `failed(String)` | Discovery failed with error message |

**Computed Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `description` | `String` | Human-readable description of result |

---

## Entry Points

### MeshCore OneApp (@main)

The main entry point. Initializes `AppState` with a SwiftData `ModelContainer` and injects it into the environment.

### ContentView

**File:** `MC1/ContentView.swift`

Root view that switches between `OnboardingView()` and `MainTabView()` based on `appState.hasCompletedOnboarding`. Manages the overall app navigation structure and coordinates with `AppState` for navigation events.

---

## See Also

- [Architecture Overview](../Architecture.md)
- [User Guide](../User_Guide.md)
