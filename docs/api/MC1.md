# MeshCore One App API Reference

The `MeshCore One` app layer manages the user interface, application lifecycle, and coordinates services.

## Target Information

- **Location:** `MC1/`
- **Type:** iOS Application
- **Dependencies:** MC1Services (which re-exports MeshCore via `@_exported import`; MeshCore is not a direct app-target dependency), Emojibase, MapLibre, and the `MC1Widgets` target

---

## AppState (public, @MainActor, @Observable class)

**File:** `MC1/State/AppState.swift` (with extensions in `MC1/State/AppState+Lifecycle.swift`, `AppState+DeviceActions.swift`, `AppState+NotificationHandlers.swift`, `AppState+Wiring.swift`)

The central state management object for the application. Navigation and onboarding state live on dedicated child objects (`navigation`, `onboarding`); connection lifecycle is delegated to `ConnectionManager`.

### Connection Properties

| Property | Type | Description |
|----------|------|-------------|
| `connectionManager` | `ConnectionManager` | Source of truth for device connection |
| `connectionState` | `DeviceConnectionState` | Convenience accessor for connection status |
| `services` | `ServiceContainer?` | Business logic services (when connected) |
| `syncCoordinator` | `SyncCoordinator?` | Coordinates background sync operations |
| `connectedDevice` | `DeviceDTO?` | Currently connected device information |
| `currentRadioID` | `UUID?` | Connected (or last-connected) radio's data-partition UUID |

### Child State Objects

| Property | Type | Description |
|----------|------|-------------|
| `navigation` | `NavigationCoordinator` | Tab selection, pending navigation targets, cross-tab navigation |
| `onboarding` | `OnboardingState` | Onboarding completion flag and navigation path |
| `connectionUI` | `ConnectionUIState` | Status pills, sync activity, alerts, pairing |
| `batteryMonitor` | `BatteryMonitor` | Battery polling, thresholds, low-battery notifications |
| `liveActivityManager` | `LiveActivityManager` | Live Activity lifecycle (Lock Screen, Dynamic Island) |

### Navigation Methods (on `appState.navigation`)

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
| `completeOnboarding()` | Marks onboarding as complete (delegates to `onboarding`) |

### UI Coordination

| Property | Type | Description |
|----------|------|-------------|
| `messageEventStream` | `MessageEventStream` | AsyncStream distribution of `MessageEvent` to chat/room consumers |
| `messageEventDispatcher` | `MessageEventDispatcher` | Routes service event streams into `messageEventStream` |
| `statusPillState` | `StatusPillState` | Computed status pill (failed/syncing/ready/connecting/disconnected/hidden) |
| `servicesVersion` | `Int` | Incremented to trigger view reloads when services change |
| `contactsVersion` | `Int` | Incremented to trigger contact list updates |
| `conversationsVersion` | `Int` | Incremented to trigger conversation list updates |

---

## Message event distribution

Service-layer events reach chat and room views through two collaborators owned by `AppState`, replacing the old single broadcaster:

- **`MessageEventStream`** (`MC1/State/MessageEventStream.swift`, `@MainActor`): a fan-out distributor. Consumers call `events()` to obtain a fresh `AsyncStream<MessageEvent>` and consume it from a SwiftUI `.task` block (cancellation propagates on view disappear). `send(_:)` yields to every live continuation.
- **`MessageEventDispatcher`** (`MC1/State/MessageEventDispatcher.swift`, `@MainActor`): subscribes to the service event streams (`SyncCoordinator`, `HeardRepeatsService`, `RemoteNodeService`, `RoomServerService`, `MessageService`) in `wire(services:)` and forwards resolved events into `MessageEventStream`. `cancelAll()` tears down on re-wire and disconnect.

### Event Types

**File:** `MC1/State/MessageEvent.swift`

`MessageEvent` carries MC1-local, contact-resolved DTOs (not the firmware-wire `MeshEvent`). Consumers switch exhaustively so a new case is a compile error.

```swift
enum MessageEvent: Sendable, Equatable {
    case directMessageReceived(message: MessageDTO, contact: ContactDTO)
    case channelMessageReceived(message: MessageDTO, channelIndex: UInt8)
    case roomMessageReceived(message: RoomMessageDTO, sessionID: UUID)
    case messageStatusResolved(messageID: UUID, status: MessageStatus, roundTripTime: UInt32? = nil)
    case messageResent(messageID: UUID)
    case messageFailed(messageID: UUID)
    case messageRetrying(messageID: UUID, attempt: Int, maxAttempts: Int)
    case heardRepeatRecorded(messageID: UUID, count: Int)
    case reactionReceived(messageID: UUID, summary: String)
    case routingChanged(contactID: UUID, isFlood: Bool)
    case roomMessageStatusUpdated(messageID: UUID)
    case roomMessageFailed(messageID: UUID)
}
```

---

## ViewModels

### ChatViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Chats/ViewModel/ChatViewModel.swift` (with extensions in `MC1/Views/Chats/ViewModel/`)

Manages state for both the conversation list and a single chat conversation. Per-conversation timeline state lives in a shared `ChatCoordinator` (bound in `configure(...)`); the view model forwards `messages`, `renderState`, and `items` to it.

| Property | Type | Description |
|----------|------|-------------|
| `messages` | `[MessageDTO]` | Conversation messages (forwarded from the bound coordinator) |
| `currentContact` | `ContactDTO?` | Current chat contact |
| `currentChannel` | `ChannelDTO?` | Current channel being viewed |
| `conversations` | `[ContactDTO]` | Current conversations (contacts with messages) |
| `channels` | `[ChannelDTO]` | Current channels with messages |
| `roomSessions` | `[RemoteNodeSessionDTO]` | Current room sessions |
| `isLoading` | `Bool` | Loading state |
| `composingText` | `String` | Message text being composed |
| `errorMessage` | `String?` | Modal load/fetch error |
| `sendErrorMessage` | `String?` | Modal send-only failure ("Unable to Send") |
| `errorBannerMessage` | `String?` | Non-modal passive-failure banner |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadConversations(radioID:) async` | Load the conversation list (contacts, channels, rooms) |
| `loadMessages(for:) async` | Load messages for a contact |
| `sendMessage(text:) async` | Send message to current contact |
| `retryMessage(_:) async` | Retry failed message with flood routing |
| `loadChannelMessages(for:) async` | Load messages for a channel |
| `sendChannelMessage(text:) async` | Send message to current channel |

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
| `loadContacts(radioID:) async` | Load contacts from local database |
| `syncContacts(radioID:) async` | Sync contacts from device |
| `filteredContacts(searchText:segment:sortOrder:userLocation:)` | Returns contacts filtered by segment and sorted |
| `toggleFavorite(contact:) async` | Toggle favorite status |
| `toggleBlocked(contact:) async` | Toggle blocked status |
| `deleteContact(_:) async` | Remove a contact from the radio and local store |

### MapViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Map/MapViewModel.swift`

Manages state for the map view showing contact locations.

| Property | Type | Description |
|----------|------|-------------|
| `visibleContacts` | `[ContactDTO]` | Filter-visible contacts shown as pins |
| `visibleDiscovered` | `[DiscoveredNodeDTO]` | Filter-visible discovered nodes shown as pins |
| `allLocatedContacts` | `[ContactDTO]` | Unfiltered located contacts (cache for warm re-filter) |
| `allLocatedDiscovered` | `[DiscoveredNodeDTO]` | Unfiltered plottable discovered nodes (cache for warm re-filter) |
| `mapPoints` | `[MapPoint]` | Map points from contacts, discovered nodes, plus any dropped pin |
| `focusedPin` | `MapPoint?` | A user-dropped pin from a chat coordinate tap |
| `cameraRegion` | `MKCoordinateRegion?` | Map viewport region |
| `isLoading` | `Bool` | Loading state |
| `hasPinsForCenterAll` | `Bool` | Whether Center All has any contact or discovered pin |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadMapData(filter:showsLoadingChrome:) async` | Load located contacts and discovered nodes, then apply `MapFilterState` pin algebra |
| `applyFilter(_:)` | Warm pin algebra from cached located tables (no reload) |
| `scheduleFilterChange(_:)` | Debounced filter apply; cold path loads when cache empty |
| `scheduleCoalescedReload(filter:showsLoadingChrome:)` | Debounced live reload; trailing filter wins |
| `focusOnCoordinate(_:)` | Drop a pin at a coordinate and center on it |
| `centerOnAllContacts()` | Center map on contact ∪ discovered pin coordinates |

---

### Diagnostic ViewModels

### LineOfSightViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift`

Manages state and calculations for RF line of sight analysis, including optional relay (repeater) analysis.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `pointA` | `SelectedPoint?` | Starting point of analysis (contact or dropped pin) |
| `pointB` | `SelectedPoint?` | Target point of analysis |
| `repeaterPoint` | `RepeaterPoint?` | Optional relay point (on-path or relocated off-path) |
| `frequencyMHz` | `Double` | Operating frequency in MHz |
| `refractionK` | `Double` | Refraction k-factor (auto-triggers re-analysis on change) |
| `elevationProfile` | `[ElevationSample]` | Terrain elevation samples along the A-to-B path |
| `analysisStatus` | `AnalysisStatus` | Current analysis state (`.idle`, `.result`, `.relayResult`, `.error`) |
| `isAnalyzing` | `Bool` | Whether analysis is in progress |
| `mapPoints` | `[MapPoint]` | Map annotations for points, repeaters, and obstructions |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `analyze()` | Fetches elevation and performs the A-to-B clearance analysis |
| `analyzeWithRepeater()` | Performs two-segment relay analysis via the repeater |
| `loadRepeaters() async` | Loads repeater contacts with locations |
| `selectPoint(at:from:)` | Auto-assigns a tapped coordinate to point A then B |
| `addRepeater()` | Adds a repeater at the worst obstruction point |
| `clear()` | Clears points, repeater, and analysis state |

### TracePathViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Tools/TracePath/TracePathViewModel.swift`

Manages manual path construction, single and batch path tracing, and saved-path runs for network routing.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `outboundPath` | `[PathHop]` | The hops the user has built (mirrored to a return path when `autoReturnPath`) |
| `availableRepeaters` | `[ContactDTO]` | Repeater contacts available to add as hops |
| `result` | `TraceResult?` | Most recent trace result |
| `activeSavedPath` | `SavedTracePathDTO?` | The saved path currently bound (runs are appended to it) |
| `isRunning` | `Bool` | Trace in progress |
| `batchEnabled` | `Bool` | Whether batch (repeated) trace mode is on |
| `errorMessage` | `String?` | Error message (auto-clears after a delay) |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `loadContacts(radioID:) async` | Loads repeaters, rooms, and discovered nodes for name resolution |
| `addNode(_:)` | Appends a node to the outbound path |
| `runTrace() async` | Sends a single trace and awaits the response |
| `runBatchTrace() async` | Runs `batchSize` sequential traces and aggregates results |
| `savePath(name:) async` | Saves the current traced path with the given name |
| `startListening()` / `stopListening()` | Subscribe to / cancel trace-response events |

**Note**: The Trace Path tool uses manual path construction where users select and order repeaters. Automatic path discovery (e.g., breadth-first search) is not currently implemented.

### RxLogViewModel (internal, @MainActor, @Observable class)

**File:** `MC1/Views/Tools/RxLogViewModel.swift`

Manages RF packet log display for network diagnostics, subscribing to the live `RxLogService` while visible.

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `entries` | `[RxLogEntryDTO]` | Captured RF packet log entries (newest first) |
| `groupCounts` | `[String: Int]` | Count of entries per packet hash |
| `routeFilter` | `RouteFilter` | Filter by route (all / flood-only / direct-only) |
| `decryptFilter` | `DecryptFilter` | Filter by decrypt outcome (all / decrypted / failed) |
| `nodeNames` | `[Data: String]` | Public-key-prefix to display-name map for hop resolution |
| `filteredEntries` | `[RxLogEntryDTO]` | Entries after applying the current filters |

**Key Methods:**

| Method | Description |
|--------|-------------|
| `subscribe() async` | Loads existing entries and streams live updates from `RxLogService` |
| `unsubscribe()` | Stops the live update stream |
| `clearLog() async` | Clears all captured entries |
| `loadNodeNames() async` | Builds the path-hash to contact-name map |
| `setRouteFilter(_:)` / `setDecryptFilter(_:)` | Update the active filters |

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
| `notificationLevel` | `NotificationLevel` | Effective notification level for the conversation |
| `isMuted` | `Bool` | Whether notifications are muted |
| `isFavorite` | `Bool` | Whether the conversation is favorited |

### OnboardingStep (enum)

**File:** `MC1/State/OnboardingState.swift`

Represents the steps in the onboarding flow. Onboarding state lives on `OnboardingState` (accessed via `appState.onboarding`), which holds `hasCompletedOnboarding`, the `onboardingPath`, and `suggestedStartingPath(...)`.

```swift
enum OnboardingStep: String, CaseIterable, Hashable, Codable {
    case welcome
    case permissions
    case pair
    case region
    case preset
}
```

**Cases:**

| Case | Description |
|------|-------------|
| `welcome` | Initial welcome screen |
| `permissions` | Bluetooth, location, and notification permissions request |
| `pair` | Device pairing and connection |
| `region` | Region selection |
| `preset` | Radio preset selection for companion devices |

### PathHop (struct)

**File:** `MC1/Views/PathEditing/PathManagementViewModel.swift`

Represents a single hop in a routing path with stable identity for SwiftUI.

```swift
struct PathHop: Identifiable, Equatable {
    let id = UUID()
    var hashBytes: Data           // Public key prefix bytes (1–3 bytes depending on hash mode)
    var publicKey: Data?          // Full 32-byte key when known (for unambiguous matching)
    var resolvedName: String?     // Contact name if resolved, nil if unknown
}
```

**Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `id` | `UUID` | Unique identifier for SwiftUI list management |
| `hashBytes` | `Data` | Public-key prefix bytes (1 to 3, depending on hash mode) |
| `publicKey` | `Data?` | Full public key when known, for unambiguous matching |
| `resolvedName` | `String?` | Contact name if resolved, nil if unknown |
| `hashHex` | `String` | Uppercase hex of `hashBytes` |
| `displayText` | `String` | Formatted display text (name + hash or just hash) |

### PathDiscoveryResult (enum)

**File:** `MC1/Views/PathEditing/PathManagementViewModel.swift`

Result of a path discovery operation.

```swift
enum PathDiscoveryResult: Equatable {
    case success(hopCount: Int)
    case noPathFound
    case failed(String)
}
```

**Cases:**

| Case | Description |
|------|-------------|
| `success(hopCount:)` | Path discovered successfully with hop count |
| `noPathFound` | Remote node did not respond to discovery |
| `failed(String)` | Discovery failed with error message |

**Computed Properties:**

| Property | Type | Description |
|----------|------|-------------|
| `description` | `String` | Human-readable description of result |

---

## Entry Points

### MC1App (@main)

**File:** `MC1/MC1App.swift`

The main entry point. Initializes `AppState` with a SwiftData `ModelContainer` and injects it into the environment.

### ContentView

**File:** `MC1/ContentView.swift`

Root view that switches between `OnboardingView()` and the connected UI based on `appState.onboarding.hasCompletedOnboarding`. The connected UI is `MainSidebarView()` on a regular horizontal size class (iPad) and `MainTabView()` on compact. Manages the overall app navigation structure and coordinates with `AppState` for navigation events.

---

## See Also

- [Architecture Overview](../Architecture.md)
- [User Guide](../User_Guide.md)
