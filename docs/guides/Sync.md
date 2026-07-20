# Sync Guide

This guide covers the SyncCoordinator, connection lifecycle phases, and sync flows in MeshCore One.

## Overview

When MeshCore One connects to a MeshCore device, it must synchronize local data with the device's state. The `SyncCoordinator` orchestrates this process through three phases: contacts, channels, and messages.

## SyncCoordinator

**File:** `MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift`

```swift
public actor SyncCoordinator {
    @MainActor private(set) var lastSyncDate: Date?
}

enum SyncState: Sendable, Equatable {
    case idle
    case syncing(progress: SyncProgress)
    case synced
    case failed(SyncCoordinatorError)
}

struct SyncProgress: Sendable, Equatable {
    let phase: SyncPhase
    let current: Int
    let total: Int
}

public enum SyncPhase: Sendable, Equatable {
    case contacts
    case channels
    case messages
}
```

## Connection Lifecycle

```
BLE Connected
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  1. WIRE MESSAGE HANDLERS                                   │
│     Set up callbacks BEFORE events can arrive               │
│     • Contact message handler (textType = 0x00)             │
│     • Channel message handler (textType = 0x03)             │
│     • Signed message handler (textType = 0x02, room servers)│
│     • CLI message handler (textType = 0x01, repeater admin) │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  2. START EVENT MONITORING (NO AUTO-FETCH YET)              │
│     Begin processing events from device                     │
│     Handlers are ready to receive                           │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  3. EXPORT PRIVATE KEY                                      │
│     Used for direct message decryption in RxLogService      │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  4. PERFORM FULL SYNC                                       │
│     Synchronize data in order:                              │
│     • Contacts (with UI pill)                               │
│     • Channels (with UI pill, foreground only)              │
│     • Messages (no UI pill)                                 │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  5. START DISCOVERY EVENT MONITORING                        │
│     Set up callbacks for ongoing discovery:                 │
│     • New contact discovered                                │
│     • Contact sync request (auto-add mode)                  │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  6. FLUSH DEFERRED ADVERT FETCHES                           │
│     Stop suppressing advert-driven contact fetches          │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  7. DRAIN PENDING HANDLERS                                  │
│     Wait up to 30s, then resume notifications               │
└─────────────────────────────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────────────────────────────┐
│  8. START AUTO-FETCH                                        │
│     After suppression is cleared, to avoid notification spam│
└─────────────────────────────────────────────────────────────┘
      │
      ▼
Connection Ready
```

### Critical Order

The order is critical:

1. **Handlers first:** If events arrive before handlers are wired, messages are lost
2. **Event monitoring second:** Start monitoring with auto-fetch disabled
3. **Export private key:** Needed for direct message decryption in RxLogService
4. **Sync:** Pull current state from device (contacts → channels → messages)
5. **Discovery monitoring after sync:** For ongoing contact discovery after initial sync
6. **Flush deferred advert fetches:** Stop suppressing advert-driven contact fetches now that monitoring is live
7. **Drain pending handlers:** Wait for in-flight handlers, then resume notifications so sync-time messages stay suppressed
8. **Auto-fetch last:** Starts after suppression is cleared to avoid notification spam

## Sync Phases

### Phase 1: Contact Sync

```swift
// SyncCoordinator.performFullSync()
syncState = .syncing(progress: SyncProgress(phase: .contacts, current: 0, total: 0))
await onSyncStarted?()  // Shows UI pill

let result = try await contactService.syncContacts(
    radioID: radioID,
    since: lastContactSync  // Incremental if available
)
```

**ContactService.syncContacts:**

```swift
// Fetch from device
let meshContacts = try await session.getContacts(since: lastSync)

var receivedCount = 0
var lastTimestamp: UInt32 = 0

// Save each to local database
for meshContact in meshContacts {
    let frame = meshContact.toContactFrame()
    _ = try await dataStore.saveContact(radioID: radioID, from: frame)
    receivedCount += 1

    let modifiedTimestamp = UInt32(meshContact.lastModified.timeIntervalSince1970)
    if modifiedTimestamp > lastTimestamp {
        lastTimestamp = modifiedTimestamp
    }
}

return ContactSyncResult(
    contactsReceived: receivedCount,
    lastSyncTimestamp: lastTimestamp,
    isIncremental: lastSync != nil
)
```

### Phase 2: Channel Sync

```swift
syncState = .syncing(progress: SyncProgress(phase: .channels, current: 0, total: 0))

let maxChannels = device?.maxChannels ?? 0
let result = try await channelService.syncChannels(
    radioID: radioID,
    maxChannels: maxChannels
)
```

**ChannelService.syncChannels:**

```swift
// Query each slot up to the device's channel capacity
for index: UInt8 in 0..<maxChannels {
    let config = try await session.getChannel(index: index)

    if let config {
        try await dataStore.saveChannel(radioID: radioID, index: index, config: config)
    }
}

Channel sync is skipped when the app is in the background to avoid long-running BLE operations.
```

### Phase 3: Message Sync

```swift
// Note: No UI pill for message phase
await onSyncEnded?()  // Hides UI pill

syncState = .syncing(progress: SyncProgress(phase: .messages, current: 0, total: 0))

await messagePollingService.pollAllMessages()
```

**MessagePollingService.pollAllMessages:**

```swift
var count = 0

while true {
    let result = try await session.getMessage()

    switch result {
    case .noMoreMessages:
        return count  // Queue empty

    case .contactMessage(let message):
        // Handled by event monitoring handlers
        count += 1

    case .channelMessage(let message):
        // Handled by event monitoring handlers
        count += 1

    case .channelDatagram:
        // Binary datagram (firmware v11+); not a user-visible message,
        // so drain the queue without counting it
        break
    }
}
```

## Incremental vs Full Sync

### Incremental Sync

Used when we have a previous sync timestamp:

```swift
// Only fetch contacts modified since last sync
let contacts = try await session.getContacts(since: lastSyncDate)
```

Benefits:
- Faster sync
- Less data transfer
- Lower battery usage

### Full Sync

Used on first connection or when data may be stale:

```swift
// Fetch all contacts
let contacts = try await session.getContacts(since: nil)
```

When to use:
- First connection ever
- Device was reset
- Long time since last sync
- Data corruption suspected

## Sync Activity Callbacks

The coordinator provides callbacks for UI feedback:

```swift
public func setSyncActivityCallbacks(
    onStarted: @escaping @Sendable () async -> Void,
    onEnded: @escaping @Sendable (_ succeeded: Bool) async -> Void,
    onPhaseChanged: @escaping @Sendable @MainActor (_ phase: SyncPhase?) -> Void
) async
```

### UI Pill Display

```swift
// ConnectionUIState tracks sync activity via counter; pill shows when > 0
var syncActivityCount: Int = 0

// SyncCoordinator calls these during contacts/channels phases
await onSyncActivityStarted?()           // syncActivityCount += 1
await onSyncActivityEnded?(succeeded)    // syncActivityCount -= 1
```

The pill is shown for:
- Contacts sync phase
- Channels sync phase
- On-demand settings operations

The pill is NOT shown for message sync because:
- Message polling can take variable time
- Users shouldn't wait for it
- It happens in background

## Error Handling

### Sync Errors

```swift
public enum SyncCoordinatorError: Error, Sendable {
    case notConnected
    case syncFailed(String)
    case alreadySyncing
}
```

### Recovery Strategy

```swift
do {
    try await performFullSync(
        radioID: radioID,
        dataStore: dataStore,
        contactService: contactService,
        channelService: channelService,
        messagePollingService: messagePollingService
    )
    await setState(.synced)
} catch {
    let syncError = SyncCoordinatorError.syncFailed(error.localizedDescription)
    await setState(.failed(syncError))

    // Log for debugging
    logger.error("Sync failed: \(error)")
}
```

On failure:
1. State transitions to `.failed(SyncCoordinatorError)`
2. UI shows error indicator
3. User can trigger manual retry via pull-to-refresh

## Message Handler Wiring

### Contact Message Handler

The contact and channel handlers both forward into a shared `handleIncomingMessage` pipeline (timestamp correction, RX-log path correlation, dedup, reaction short-circuit, persistence, unread/notification updates, UI refresh). The snippet below illustrates the direct-message path:

```swift
// Handles direct messages from contacts (textType = 0x00)
await messagePollingService.setContactMessageHandler { message, contact, context in
    let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

    // Create DTO
    let messageDTO = MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: contact?.id,
        channelIndex: nil,
        text: message.text,
        timestamp: timestamp,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: TextType(rawValue: message.textType) ?? .plain,
        ackCode: nil,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderPublicKeyPrefix,
        senderNodeName: nil,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )

    // Save to database
    try await dataStore.saveMessage(messageDTO)

    // Update contact's last message date and unread count
    if let contactID = contact?.id {
        try await dataStore.updateContactLastMessage(contactID: contactID, date: Date())
        try await dataStore.incrementUnreadCount(contactID: contactID)
    }

    // Post notification
    if let contactID = contact?.id {
        await services.notificationService.postDirectMessageNotification(
            from: contact?.displayName ?? "Unknown",
            contactID: contactID,
            messageText: message.text,
            messageID: messageDTO.id
        )
    }
    await services.notificationService.updateBadgeCount()

    // Notify UI via SyncCoordinator
    await syncCoordinator.notifyConversationsChanged()

    // Broadcast for real-time chat updates via the data event stream
    if let contact {
        dataEventBroadcaster.yield(.directMessageReceived(message: messageDTO, contact: contact))
    }
}
```

### Channel Message Handler

```swift
// Handles channel broadcast messages (textType = 0x03)
await messagePollingService.setChannelMessageHandler { message, channel, context in
    // Parse "NodeName: text" format for sender name
    let (senderNodeName, messageText) = parseChannelMessage(message.text)

    let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)
    let messageDTO = MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: nil,
        channelIndex: message.channelIndex,
        text: messageText,
        timestamp: timestamp,
        createdAt: Date(),
        direction: .incoming,
        status: .delivered,
        textType: TextType(rawValue: message.textType) ?? .plain,
        ackCode: nil,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: nil,
        senderNodeName: senderNodeName,
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
    )

    // Save to database
    try await dataStore.saveMessage(messageDTO)

    // Update channel's last message date and unread count
    if let channelID = channel?.id {
        try await dataStore.updateChannelLastMessage(channelID: channelID, date: Date())
        try await dataStore.incrementChannelUnreadCount(channelID: channelID)
    }

    // Post notification
    await services.notificationService.postChannelMessageNotification(
        channelName: channel?.name ?? "Channel \(message.channelIndex)",
        channelIndex: message.channelIndex,
        radioID: radioID,
        senderName: senderNodeName,
        messageText: messageText,
        messageID: messageDTO.id
    )
    await services.notificationService.updateBadgeCount()

    // Notify UI via SyncCoordinator
    await syncCoordinator.notifyConversationsChanged()

    // Broadcast for real-time chat updates via the data event stream
    dataEventBroadcaster.yield(.channelMessageReceived(message: messageDTO, channelIndex: message.channelIndex))
}

// Helper function to parse channel messages
private static func parseChannelMessage(_ text: String) -> (senderNodeName: String?, messageText: String) {
    let parts = text.split(separator: ":", maxSplits: 1)
    if parts.count > 1 {
        let senderName = String(parts[0]).trimmingCharacters(in: .whitespaces)
        let messageText = String(parts[1]).trimmingCharacters(in: .whitespaces)
        return (senderName, messageText)
    }
    return (nil, text)
}
```

### Signed Message Handler

```swift
// Handles signed messages from room servers (textType = 0x02)
await messagePollingService.setSignedMessageHandler { message, contact in
    // For signed room messages, the signature contains the 4-byte author key prefix
    guard let authorPrefix = message.signature?.prefix(4), authorPrefix.count == 4 else {
        logger.warning("Dropping signed message: missing or invalid author prefix")
        return
    }

    let timestamp = UInt32(message.senderTimestamp.timeIntervalSince1970)

    // Process room server message
    try await roomServerService.handleIncomingMessage(
        senderPublicKeyPrefix: message.senderPublicKeyPrefix,
        timestamp: timestamp,
        authorPrefix: Data(authorPrefix),
        text: message.text
    )
}
```

### CLI Message Handler

```swift
// Handles CLI messages from repeater/room admin (textType = 0x01)
await messagePollingService.setCLIMessageHandler { message, contact in
    // Route CLI responses by contact type: rooms to roomAdminService,
    // everything else (repeaters) to repeaterAdminService
    if let contact {
        if contact.type == .room {
            await roomAdminService.invokeCLIHandler(message, fromContact: contact)
        } else {
            await repeaterAdminService.invokeCLIHandler(message, fromContact: contact)
        }
    } else {
        logger.warning("Dropping CLI response: no contact found for sender")
    }
}
```

## Message Event Callbacks

The SyncCoordinator yields incoming-message events on the same `dataEvents()` broadcaster used for data-change events. These are separate from the handlers above and are consumed by `MessageEventDispatcher` for live chat updates.

### Wiring the Message Event Stream

```swift
// MessageEventDispatcher.wireSyncCoordinator subscribes to the data event stream
let events = syncCoordinator.dataEvents()
let task = Task { [weak appState, stream] in
    for await event in events {
        switch event {
        case .directMessageReceived(let message, let contact):
            stream.send(.directMessageReceived(message: message, contact: contact))
        case .channelMessageReceived(let message, let channelIndex):
            stream.send(.channelMessageReceived(message: message, channelIndex: channelIndex))
        case .roomMessageReceived(let message):
            stream.send(.roomMessageReceived(message: message, sessionID: message.sessionID))
        case .reactionReceived(let messageID, let summary):
            stream.send(.reactionReceived(messageID: messageID, summary: summary))
            await appState?.handleReactionNotification(messageID: messageID)
        case .contactsChanged, .conversationsChanged:
            break
        }
    }
}
```

### How Event Callbacks Work

When a message arrives:

1. **Message Handler** (in SyncCoordinator):
   - Saves message to database
   - Updates unread counts
   - Posts system notification
   - Updates UI refresh counters

2. **Event Stream** (consumed by MessageEventDispatcher):
   - Broadcasts to open chat views
   - Updates message lists in real-time
   - Handles message status updates
   - Updates chat UI without database reload

This separation ensures:
- Messages are persisted immediately
- Open chats update instantly
- Closed chats show notifications
- No duplicate database queries

## Message Filtering and Deduplication

- **Deduplication:** Persistent dedup via `deduplicationKey` on `Message`. Uses the RX log packet hash when available, falling back to a content-based key. Checked before save via `isDuplicateMessage()`.
- **Blocked contacts:** `SyncCoordinator` caches blocked contact names for O(1) checks during polling.

## Discovery Handlers

Discovery is consumed as an event stream in `SyncCoordinator.startDiscoveryEventMonitoring`, started only after the initial sync so adverts arriving during sync do not spam notifications:

```swift
func startDiscoveryEventMonitoring(dependencies: SyncDependencies, radioID: UUID) {
    discoveryEventsTask?.cancel()
    let events = dependencies.advertisementService.events()
    discoveryEventsTask = Task { [weak self] in
        for await event in events {
            guard let self else { return }
            switch event {
            case .newContactDiscovered(let name, let contactID, let contactType):
                // New contact discovered via advertisement (manual-add 0x8A or
                // auto-add 0x80 getContact save). UI refresh + optional notification.
                await dependencies.notificationService.postNewContactNotification(
                    contactName: name,
                    contactID: contactID,
                    contactType: contactType
                )
                await self.notifyContactsChanged()
            case .contactUpdated, .nodeStorageFullChanged, .contactDeletedCleanup,
                 .pathDiscoveryResponse, .traceResponse, .traceSnrObserved:
                break
            }
        }
    }
}
```

## Disconnection Handling

When the device disconnects, the sync state resets:

```swift
// Called by ConnectionManager when disconnecting
await syncCoordinator.onDisconnected(notificationService: notificationService)

// In SyncCoordinator (decrements activity if mid-sync, then resets state):
func onDisconnected(notificationService: NotificationService) async {
    // ... end sync activity if mid-contacts/channels, clear guards ...
    await setState(.idle)
}

// In AppState.wireServicesIfConnected:
guard let services else {
    tearDownAppStateSessionState()
    // Clear syncCoordinator when services are nil
    syncCoordinator = nil
    // handleDisconnect resets syncActivityCount = 0 to prevent a stuck pill
    connectionUI.handleDisconnect(...)
    return
}
```

This ensures:
- Sync state transitions to `.idle`
- Sync activity count resets to 0
- UI pill is hidden
- Clean state when reconnecting
- No stale sync indicators

## Observable State for SwiftUI

The coordinator provides observable counters for SwiftUI updates. Since actors don't participate in SwiftUI's observation system, the coordinator yields data-change events on a broadcaster that AppState consumes to bump its own version counters.

### SyncCoordinator Version Counters

```swift
// In SyncCoordinator (actor)
@MainActor private(set) var contactsVersion: Int = 0
@MainActor private(set) var conversationsVersion: Int = 0

@MainActor
public func notifyContactsChanged() {
    contactsVersion += 1
    dataEventBroadcaster.yield(.contactsChanged)
}

@MainActor
public func notifyConversationsChanged() {
    conversationsVersion += 1
    dataEventBroadcaster.yield(.conversationsChanged)
}
```

### Wiring the Data Event Stream to AppState

```swift
// In AppState.wireSyncDataEvents
let events = services.syncCoordinator.dataEvents()
syncDataEventsTask = Task { [weak self] in
    for await event in events {
        guard let self else { return }
        switch event {
        case .contactsChanged:
            self.contactsVersion += 1
        case .conversationsChanged:
            self.refreshConversations()
        case .directMessageReceived, .channelMessageReceived, .roomMessageReceived, .reactionReceived:
            break  // owned by MessageEventDispatcher
        }
    }
}
```

### SwiftUI Views Observing Changes

```swift
struct ContactsView: View {
    @Environment(\.appState) private var appState

    var body: some View {
        List(contacts) { contact in
            ContactRow(contact: contact)
        }
        .onChange(of: appState.contactsVersion) { _, _ in
            // Reload contacts
            Task { await loadContacts() }
        }
    }
}
```

## See Also

- [SyncCoordinator API](../api/MC1Services.md#synccoordinator-public-actor)
- [Architecture Overview](../Architecture.md)
- [Messaging Guide](Messaging.md)
