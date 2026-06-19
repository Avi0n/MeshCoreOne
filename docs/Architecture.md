# MeshCore One Architecture

MeshCore One is built using a modern, three-tier modular architecture designed for high performance, reliability, and maintainability. It leverages Swift 6's strict concurrency model, actor isolation, and modern iOS frameworks.

## Platform Requirements

- **Minimum Deployment:** iOS 18.0
- **Feature Target:** iOS 26.0 (uses `#available` checks for newer APIs)
- **Swift:** 6.2+
- **Xcode:** 26.0+

## High-Level Architecture

The project is divided into three main layers:

1. **MeshCore (Protocol Layer)**: A pure Swift implementation of the MeshCore mesh networking protocol.
2. **MC1Services (Business Logic Layer)**: Manages higher-level business logic, actor isolation, and shared persistence.
3. **MeshCore One (UI Layer)**: The SwiftUI-based user interface and application state management.

```mermaid
graph TD
    subgraph MeshCore One [UI Layer]
        AppState[AppState @Observable @MainActor]
        Views[SwiftUI Views]
        MEB[MessageEventDispatcher @MainActor]
        LSVM[LineOfSightViewModel]
        ES[ElevationService actor]
        LS[LocationService @MainActor @Observable class]
        LPS[LinkPreviewService final class]
        LES[LogExportService enum]
    end

    subgraph MC1Services [Business Logic Layer]
        ServiceContainer[ServiceContainer DI]
        CM[ConnectionManager @MainActor @Observable]
        SC[SyncCoordinator actor]

        subgraph CoreServices [Core Services]
            MS[MessageService actor]
            CS[ContactService actor]
            CH[ChannelService actor]
            SS[SettingsService actor]
            AS[AdvertisementService actor]
            MPS[MessagePollingService actor]
            BPS[BinaryProtocolService actor]
            DS[DeviceService actor]
            HRS[HeardRepeatsService actor]
            RXS[RxLogService actor]
        end

        subgraph RemoteServices [Remote Node Services]
            RNS[RemoteNodeService actor]
            RAS[RepeaterAdminService actor]
            RSS[RoomServerService actor]
        end

        subgraph IndependentServices [Independent Services]
            KS[KeychainService actor]
            NS[NotificationService @MainActor @Observable class]
            DLB[DebugLogBuffer actor]
            RS[ReactionService actor]
        end

        PS[PersistenceStore @ModelActor actor]
    end

    subgraph MeshCore [Protocol Layer]
        MCS[MeshCoreSession actor]
        ED[EventDispatcher]
        PB[PacketBuilder enum]
        PP[PacketParser enum]
    end

    subgraph Transport [Transport Layer - in MC1Services]
        BT[iOSBLETransport]
        BSM[BLEStateMachine]
    end

    subgraph WiFiTransportLayer [Transport Layer - in MeshCore]
        WFT[WiFiTransport]
    end

    Views --> AppState
    AppState --> CM
    AppState --> MEB
    LSVM --> ES
    AppState --> LS
    CM --> ServiceContainer
    CM --> SC
    CM --> MCS
    ServiceContainer --> MS
    ServiceContainer --> CS
    ServiceContainer --> CH
    ServiceContainer --> SS
    ServiceContainer --> AS
    ServiceContainer --> MPS
    ServiceContainer --> BPS
    ServiceContainer --> RNS
    ServiceContainer --> RAS
    ServiceContainer --> RSS
    ServiceContainer --> KS
    ServiceContainer --> NS
        ServiceContainer --> RXS
        ServiceContainer --> DLB
        ServiceContainer --> RS
    ServiceContainer --> DS
    ServiceContainer --> HRS
    SC --> MS
    SC --> CS
    SC --> CH
    SC --> MPS
    MEB --> SC
    MS --> MCS
    CS --> MCS
    CH --> MCS
    SS --> MCS
    AS --> MCS
    MPS --> MCS
    BPS --> MCS
    RNS --> MCS
    RAS --> RNS
    RSS --> RNS
    RNS --> KS
    MCS --> ED
    CM --> BT
    BT --> MCS
    MCS --> PB
    MCS --> PP
    BT --> BSM
    CM --> WFT
    WFT --> MCS
    MS --> PS
    CS --> PS
    CH --> PS
    MPS --> PS
    AS --> PS
    BPS --> PS
    RNS --> PS
    RAS --> PS
    RSS --> PS
    DS --> PS
    HRS --> PS
```

---

## 1. MeshCore (Protocol Layer)

The foundation of the project, responsible for low-level communication with MeshCore devices.

- **Actor-Based**: `MeshCoreSession` is an actor that serializes all device communication, ensuring thread safety.
- **Event-Driven**: Uses an `EventDispatcher` to broadcast `MeshEvent`s via `AsyncStream`.
- **Stateless Protocol Handlers**: `PacketBuilder` and `PacketParser` are stateless enums that handle the binary encoding/decoding of the Companion Radio Protocol.
- **Transport Abstraction**: The `MeshTransport` protocol allows for different underlying transports. `MockTransport` and `WiFiTransport` live in MeshCore. MC1Services provides `iOSBLETransport` (CoreBluetooth + `BLEStateMachine`) for production BLE on iOS, plus `SimulatorMockTransport` for the simulator.
- **Dual Transport Support**: Supports both Bluetooth Low Energy (BLE) and WiFi transports simultaneously. Each transport has its own state machine for managing connection lifecycle.
- **Auto-Reconnection**: Both transports automatically reconnect when connection is lost, with configurable retry logic and backoff strategies.
- **LPP Telemetry**: Includes a full implementation of the Cayenne Low Power Payload (LPP) for efficient sensor data transmission.

See: [MeshCore API Reference](api/MeshCore.md) | [BLE Transport Guide](guides/BLE_Transport.md)

---

## 2. MC1Services (Business Logic Layer)

Bridges the protocol layer and the UI, handling complex business rules and data persistence.

- **Service-Oriented**: Business logic is divided into specialized actors (`MessageService`, `ContactService`, `ChannelService`, `RemoteNodeService`, etc.).
- **Dependency Injection**: `ServiceContainer` manages the creation and wiring of all services, providing a single point of initialization for the service layer.
- **Sync Coordination**: `SyncCoordinator` orchestrates the connection lifecycle and data synchronization through contacts, channels, and messages phases.
- **Actor Isolation**: Every service is an actor, protecting internal state and coordinating asynchronous operations safely.
- **Persistence**: Uses **SwiftData** for local storage. Data is isolated per radio using the `radioID: UUID` field (an opaque partition key minted once on first pair, distinct from the volatile BLE peripheral UUID) as a namespace, ensuring each radio has its own isolated data.
- **DTO Pattern**: Sendable Data Transfer Objects (`MessageDTO`, `ContactDTO`, `DeviceDTO`, etc.) enable safe cross-actor data transfer while maintaining strict concurrency compliance.
- **Connection Management**: `ConnectionManager` (a `@MainActor` observable class) manages the lifecycle of the connection, including AccessorySetupKit pairing, BLE/WiFi transport selection, auto-reconnection, and service wiring.
- **Message Polling**: `MessagePollingService` pulls messages from the device queue and routes them to appropriate handlers.

### ServiceContainer & Dependency Injection

The `ServiceContainer` is the central dependency injection container for MC1Services. It creates and manages all services, handling the dependency graph and lifecycle:

**Independent Services** (no service dependencies):
- `KeychainService`: Secure credential storage using system keychain
- `NotificationService`: Local notification management for message alerts
- `ReactionService`: Reaction parsing, indexing, and pending-queue handling

Note: `ElevationService`, `LocationService`, `LinkPreviewService`, and `LogExportService` are app-scoped utilities in the MeshCore One UI layer, not part of the MC1Services package.

**Core Services** (depend on session/dataStore):
- `ContactService`: Contact management and synchronization
- `MessageService`: Message sending, receiving, and retry logic
- `ChannelService`: Channel (group) configuration and messaging
- `SettingsService`: Device settings and configuration management
- `AdvertisementService`: Advertisement broadcasting and path discovery
- `MessagePollingService`: Automatic message fetching from device queue
- `BinaryProtocolService`: Binary protocol operations (telemetry, status, etc.)
- `DeviceService`: Device information and management
- `HeardRepeatsService`: Tracking message repeat counts for channel propagation analysis
- `RxLogService`: RF packet capture and logging for network diagnostics
- `DebugLogBuffer`: Buffered debug logging persistence for `PersistentLogger`

**Remote Node Services** (depend on other services):
- `RemoteNodeService`: Remote node session management and authentication
- `RepeaterAdminService`: Repeater administration and configuration
- `RoomServerService`: Room server operations and message routing

**Three-Phase Lifecycle**:

ServiceContainer is fully wired by its `init`: all services and their cross-service callbacks are created and connected during construction (constructor injection, with shared coordinators like `SyncCoordinator` and `ContactCleanupCoordinator` passed into the services that need them). A separate event-monitoring phase activates the live device-event listeners once a radio is connected.

```swift
// Construction: create and wire all services (constructor injection)
let container = ServiceContainer(session: meshCoreSession, modelContainer: modelContainer)

// Start event monitoring when device connects
await container.startEventMonitoring(radioID: radioID)
```

**Construction** (`init`):

All services are created with their direct dependencies (session, dataStore, shared coordinators, other services). The dependency order is:
1. `PersistenceStore` (from modelContainer)
2. Independent services: `KeychainService`, `NotificationService`, `SyncCoordinator`
3. Core services: `HeardRepeatsService`, `RxLogService`, `RemoteNodeService`, `ContactService` (with `SyncCoordinator` and a `ContactCleanupCoordinator`), `MessageService`, `ChannelService`, `SettingsService`, `DeviceService`, `AdvertisementService`, `MessagePollingService`, `BinaryProtocolService`, `DebugLogBuffer`, `ReactionService`, `NodeConfigService`, `NodeSnapshotService`
4. Remote Node services: `RepeaterAdminService`, `RoomServerService` (need `RemoteNodeService`)

Cross-service connections that can't be plain constructor scalars (path management during message retry, UI-refresh notifications via `SyncCoordinator`, the contact cleanup coordinator, pushing channel secrets to `RxLogService`, forwarding heard-repeat events) are all established here during construction. `init` also sets `DebugLogBuffer.shared = debugLogBuffer` so `PersistentLogger` can enqueue entries from anywhere.

Per-connection callbacks that depend on app-layer state (notification action forwarders, the channel-update handler, ConnectionUI sync-activity callbacks) are installed separately by `AppState.wireServicesIfConnected()` on each connection, not by `ServiceContainer.init`.

**Event Monitoring** (`startEventMonitoring(radioID:)`):

Activates event listeners on services that process live device events. Guarded by a tri-state lifecycle (`stopped`/`starting`/`active`/`stopping`) so overlapping callers cannot double-start. Only called after a device is connected:

- `HeardRepeatsService.configure(radioID:localNodeName:)`: needs device info for local node identification
- `AdvertisementService.startEventMonitoring(radioID:)`: listens for advertisement/path discovery events (gated on `enableAdvertisementMonitoring`)
- `RxLogService.startEventMonitoring(radioID:)`: captures RF packets for diagnostic viewer
- `MessageService.startEventMonitoring()` and `startAckExpiryChecking()`: listen for ACK events and expire stale sends during send-with-retry
- `RemoteNodeService.startEventMonitoring()`: listens for remote node session events
- `MessagePollingService.startMessageEventMonitoring(radioID:)`: routes polled messages to handlers
- `MessagePollingService.startAutoFetch(radioID:)`: begins periodic message polling (gated on `enableAutoFetch`)
- Debug log pruning (keeps most recent 1,000 entries)
- Node snapshot pruning (removes snapshots older than 1 year)

Monitoring is stopped symmetrically by `stopEventMonitoring()` on disconnect, which also flushes the debug log buffer.

### DTO (Data Transfer Object) Pattern

To comply with Swift 6's strict concurrency model, all SwiftData models have corresponding `Sendable` DTO types for cross-actor communication:

**Model-to-DTO Conversion**:
- `MessageDTO`: Sendable snapshot of `Message` for passing between actors
- `ContactDTO`: Sendable snapshot of `Contact` for UI and service communication
- `DeviceDTO`: Sendable snapshot of `Device` for device information
- `ChannelDTO`: Sendable snapshot of `Channel` for channel configuration
- `RemoteNodeSessionDTO`: Sendable snapshot of `RemoteNodeSession` for session state
- `RoomMessageDTO`: Sendable snapshot of `RoomMessage` for room communications

**Usage Pattern**:
```swift
// In actor-isolated service
func fetchMessages(radioID: UUID) async -> [MessageDTO] {
    let messages = await dataStore.fetchMessages(radioID: radioID)
    return messages.map { MessageDTO(from: $0) }
}

// In @MainActor UI code
let messageDTOs = await messageService.fetchMessages(radioID: radioID)
// DTOs are Sendable, safe to use across actor boundaries
```

**Benefits**:
- **Concurrency Safety**: DTOs are `Sendable`, enabling safe cross-actor transfers
- **Immutability**: Snapshot semantics prevent unintended mutations
- **Actor Isolation**: Services work with SwiftData models; UI and callbacks receive DTOs
- **Clean Boundaries**: Clear separation between persistence and business logic layers

See: [MC1Services API Reference](api/MC1Services.md) | [Sync Guide](guides/Sync.md) | [Messaging Guide](guides/Messaging.md)

---

## 3. MeshCore One (UI Layer)

A modern SwiftUI application that provides a user-friendly experience for mesh messaging.

- **AppState**: A central `@Observable` class that manages app-wide state, navigation, and coordination between the UI and services.
- **MessageEventDispatcher**: A `@MainActor` object that subscribes to `SyncCoordinator.dataEvents()` and feeds a `MessageEventStream`, bridging service-layer events to SwiftUI's `@MainActor` context for real-time UI updates.
- **SwiftUI & Modern APIs**: Built with the latest SwiftUI features, utilizing `@Observable`, environment injection, and modern navigation.
- **Onboarding Flow**: A guided experience for permissions, discovery, and device pairing.
- **iMessage-Style Chat**: Rich messaging interface with delivery status, timestamps, and metadata (SNR, path length).
- **MapKit Integration**: Displays contact locations on a map with real-time updates and type-based markers.

See: [MeshCore One API Reference](api/MeshCore One.md) | [User Guide](User_Guide.md)

---

## Concurrency & Data Flow

### Concurrency Model

MeshCore One strictly adheres to the **Swift 6 concurrency model**:

- **Actors**: Used for all services and session management to prevent data races.
- **MainActor**: UI updates, `ConnectionManager`, and `MessageEventDispatcher` are isolated to the main thread.
- **AsyncStream**: Used for all event-driven communication (BLE data -> Protocol events -> Business events -> UI updates).
- **Structured Concurrency**: Utilizes `TaskGroups` for complex asynchronous flows like message retries and contact synchronization.

### `nonisolated(unsafe)` Invariants

The service-layer packages use `nonisolated(unsafe)` only where Swift's isolation checking can't be satisfied another way, with safety maintained by convention:

**BLEStateMachine**, CBCentralManager (`BLEStateMachine.swift`):
- `var centralManager: CBCentralManager!`
- **Pattern**: CBCentralManager is not `Sendable`, but needs nonisolated access for the `bluetoothState` computed property
- **Safety**: Mutated once during initialization (assigned in init). All subsequent access is either from the actor's isolated context or from the `bluetoothState` property, which reads the atomic `state` property of CBCentralManager. Returns `.unknown` during the brief window before assignment

**BackupUserDefaults**, static keypath mapping tables (`BackupUserDefaults.swift`):
- `static let boolMappings` / `stringMappings`
- **Pattern**: `WritableKeyPath` is not `Sendable`, so the constant mapping tables are marked `nonisolated(unsafe)`
- **Safety**: Immutable `let` constants, never mutated after initialization

(`SyncCoordinator`'s callbacks are now `@MainActor`-isolated with setter methods, and `DebugLogBuffer.shared` is backed by an `OSAllocatedUnfairLock`, so neither uses `nonisolated(unsafe)` any longer. The MeshCore One app layer carries one additional use for a map font-name constant.)

### Data Flow (Receiving a Message)

1. **BLE Transport**: `BLEStateMachine` receives a notification from CoreBluetooth and yields the data to `iOSBLETransport`'s `receivedData` stream.
2. **Session Parser**: `MeshCoreSession` receives the data, uses `PacketParser` to create a `MeshEvent`, and dispatches it via `EventDispatcher`.
3. **Service Processing**: `MessagePollingService` receives the event, routes it to the appropriate handler based on message type.
4. **Persistence**: The handler creates a `MessageDTO` and saves it to `PersistenceStore`.
5. **UI Update**: `SyncCoordinator` yields a `SyncDataEvent`; `MessageEventDispatcher` receives it, feeds the `MessageEventStream`, updating observable state and triggering SwiftUI view updates.

### Data Flow (Sending a Message)

1. **User Action**: User types message in `ChatConversationView` and taps send.
2. **Service Call**: `MessageService.sendMessageWithRetry()` is called.
3. **Queue**: Message is saved to `PersistenceStore` with status `.pending`.
4. **Retry Loop**: `MessageService.sendMessageWithRetry()` attempts delivery using `MessageServiceConfig.default` (up to 5 attempts: 4 direct, switching to flood routing after 4 failed direct attempts, then 1 flood attempt).
5. **ACK Tracking**: Each send attempt waits inline for ACK using `waitForEvent()` with configurable timeout.
6. **Completion**: ACK received -> `.delivered`, or all attempts exhausted -> `.failed`.

---

## Persistence Layer

- **Technology**: SwiftData
- **Isolation**: Each MeshCore radio has its own isolated data store using `radioID: UUID` (an opaque partition key minted once on first pair) as the namespace, ensuring privacy and preventing data mixing.
- **Models**:
  - `Device`: Metadata, radio parameters, and capabilities. The `id` field is the live-connection handle (BLE peripheral identifier, or a derived UUID for WiFi-bridged radios) and is volatile; `publicKey` and `radioID` survive re-pair and backup round-trips.
  - `Contact`: Public keys, names, types (Chat, Repeater, Room), and location data. Contains `radioID` for isolation.
  - `Message`: Content, timestamps, delivery status, and mesh metadata (SNR, Path). Contains `radioID` for isolation.
  - `Channel`: Slot-based configuration for group messaging. Contains `radioID` for isolation.
  - `RemoteNodeSession`: Tracks authenticated sessions with remote repeater/room nodes.
  - `RoomMessage`: Messages exchanged in room server conversations.
- **DTOs**: Each model has a corresponding `Sendable` DTO for safe cross-actor transfers.

---

## Debug Logging Infrastructure

MeshCore One includes a comprehensive debug logging system designed to help diagnose issues in the field without requiring a developer machine.

### Architecture

The debug logging system is implemented as a two-layer pipeline:

1. **Logging Entry Point (`PersistentLogger`)**:
   - Lightweight struct that writes to OSLog and enqueues debug entries
   - Used throughout services for consistent, structured logging

2. **Buffered Persistence (`DebugLogBuffer`)**:
   - Actor that batches log entries and writes to SwiftData
   - Flushes every 5 seconds or when 50 entries are queued
   - Logs are pruned on connect to keep the most recent 1,000 entries

### Usage Pattern

```swift
// Log from any context (OSLog + buffered persistence)
logger.info("Connected to device")
logger.warning("High packet loss detected")
logger.error("Failed to parse packet: \(error.localizedDescription)")
```

### Log Categories

Logs are categorized for easy filtering:

- **Connection**: BLE/WiFi connection lifecycle, pairing, reconnection
- **Messaging**: Message sending, delivery, retries, ACK tracking
- **Sync**: Contact/channel synchronization, data updates
- **Transport**: Protocol events, packet parsing, transport errors
- **UI**: Navigation, user actions, state changes
- **Diagnostics**: Line of Sight, Trace Path, RX Log operations

### Export & Analysis

Debug logs can be exported for analysis:

- **LogExportService**: Builds a text export with device/app metadata
- **Time Range**: Last 24 hours of persisted logs (up to 1,000 entries)
- **Sharing**: Exported file can be shared via the system share sheet

---

## iPad Split-View Architecture

On iPad, MeshCore One uses a split-view layout that provides a more efficient workflow and better use of available screen real estate.

### Navigation Pattern

The split-view implementation follows Apple's iPad interface guidelines:

- **Two-Panel Layout**: List panel (left) and detail panel (right)
- **Independent Navigation Stacks**: Each panel maintains its own navigation state
- **Tab Coordination**: Chats, Nodes, Tools, and Settings use split-view; Map uses a single `NavigationStack`
- **Responsive Design**: Automatically adjusts layout based on orientation and window size

### Implementation Details

Navigation state is managed by `NavigationCoordinator`, one of AppState's extracted sub-objects:

```swift
@Observable class AppState {
    let connectionUI = ConnectionUIState()    // Status pills, sync activity, alerts
    let batteryMonitor = BatteryMonitor()     // Battery polling, thresholds
    let onboarding = OnboardingState()        // Onboarding flag and path
    let navigation = NavigationCoordinator()  // Tab selection, pending navigation
}
```

Views access sub-objects directly (e.g., `appState.navigation.selectedTab`). Cross-tab navigation uses a pending navigation mechanism — the source view sets a pending target on `NavigationCoordinator`, which the destination view picks up on tab switch.

### Panel Independence

- **List Panel**: Shows master list (chats, contacts, map annotations)
- **Detail Panel**: Shows selected item (conversation, contact details, map location)
- **State Separation**: Changes in right panel don't affect left panel state
- **Concurrent Updates**: Both panels can update independently as data changes

### Adaptive Behavior

- **Portrait**: Stacked panels (list on top, detail below)
- **Landscape**: Side-by-side panels (list on left, detail on right)
- **Regular Size Class**: Always shows both panels
- **Compact Size Class**: Shows single panel with full-screen navigation

---

## Diagnostic Tools Architecture

MeshCore One includes several diagnostic tools built on a shared infrastructure for RF analysis and network troubleshooting.

### Shared Services

- **Elevation Service**: Fetches terrain elevation data from Open-Meteo API
- **RF Calculator**: Core calculations for signal propagation, Fresnel zones, and link budget
- **Location Service**: Manages location permissions and current position for analysis

### Line of Sight Tool

**Components**:
- `LineOfSightView`: Main SwiftUI view for analysis interface
- `LineOfSightViewModel`: State management and business logic
- `RFCalculator`: RF propagation calculations (path loss, Fresnel zone clearance)
- `SegmentAnalysis`: Terrain segment analysis for obstruction detection
- `FresnelZoneRenderer`: Canvas-based visualization of Fresnel zones
- `TerrainProfileCanvas`: Custom canvas for terrain profile visualization

**Workflow**:
1. User selects target contact or enters coordinates
2. Elevation data is fetched along path (Open-Meteo API)
3. Terrain profile is generated from elevation samples
4. Fresnel zones are calculated for given frequency
5. Clearance analysis determines signal quality (green/yellow/red)
6. Visual results are rendered with clearance status

### Trace Path Tool

**Components**:
- `TracePathView`: Main view for path discovery
- `TracePathViewModel`: Path discovery and management logic
- `SavedPathsViewModel`: Management of saved routing paths
- `PathEditingSheet`: Interactive path editor with repeater selection

**Workflow**:
1. User initiates trace path from contact detail
2. App discovers available repeaters and routes to target
3. Path results are displayed with signal quality per hop
4. User can edit path by selecting different repeaters
5. Saved paths are persisted for future use and can be visualized on map

### RX Log Viewer

**Components**:
- `RxLogView`: Live packet capture viewer
- `RxLogViewModel`: Packet capture and filtering logic
- `RxLogService`: Service for packet capture and persistence

**Workflow**:
1. User opens RX Log viewer
2. Service starts capturing RF packets from transport layer
3. Packets are displayed in real-time with metadata
4. User can filter by packet type, source, or destination
5. Logs can be exported for offline analysis

---

## Further Reading

- [Development Guide](Development.md)
- [User Guide](User_Guide.md)

### API References
- [MeshCore API](api/MeshCore.md)
- [MC1Services API](api/MC1Services.md)
- [MeshCore One API](api/MeshCore One.md)

### Topic Guides
- [BLE Transport](guides/BLE_Transport.md)
- [WiFi Transport](guides/WiFi_Transport.md)
- [Messaging](guides/Messaging.md)
- [Sync](guides/Sync.md)
- [Diagnostics](guides/Diagnostics.md)
- [iPad Layout](guides/iPad_Layout.md)
