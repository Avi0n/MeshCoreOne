# Managing Contacts

Discover, store, and manage contacts in the mesh network.

## Overview

Contacts represent other nodes in the mesh network. They're discovered through advertisements and stored on the device for messaging and routing.

## Fetching Contacts

Retrieve the device's contact list:

```swift
let contacts = try await session.getContacts()

for contact in contacts {
    print("\(contact.advertisedName): \(contact.publicKeyPrefix)")
}
```

### Incremental Updates

Fetch only contacts modified since a date:

```swift
let newContacts = try await session.getContacts(since: lastFetchDate)
```

### Cached Contacts

Access locally cached contacts without a device request:

```swift
let cached = session.cachedContacts
let pending = session.cachedPendingContacts
```

Use ``MeshCoreSession/ensureContacts(force:)`` to refresh if needed:

```swift
let contacts = try await session.ensureContacts()  // Uses cache if fresh
let contacts = try await session.ensureContacts(force: true)  // Always fetches
```

## Finding Contacts

### By Name

```swift
if let contact = session.getContactByName("MyNode") {
    print("Found: \(contact.publicKeyPrefix)")
}

// Exact match
let contact = session.getContactByName("MyNode", exactMatch: true)
```

### By Public Key Prefix

```swift
// From hex string
let contact = session.getContactByKeyPrefix("a1b2c3")

// From data
let contact = session.getContactByKeyPrefix(prefixData)
```

## Adding Contacts

Add a contact to the device:

```swift
try await session.addContact(pendingContact)
```

Or with full details:

```swift
try await session.updateContact(
    publicKey: publicKey,
    type: 0,
    flags: 0,
    outPathLength: 0xFF,  // Flood routing (OUT_PATH_UNKNOWN)
    outPath: Data(),
    advertisedName: "New Contact",
    lastAdvertisement: Date(),
    latitude: 0,
    longitude: 0
)
```

## Removing Contacts

```swift
try await session.removeContact(publicKey: contact.publicKey)
```

## Pending Contacts

New contacts discovered via advertisements appear as pending:

```swift
let pending = session.cachedPendingContacts

if let newContact = pending.first {
    // Add to device
    try await session.addContact(newContact)

    // Or remove from pending
    session.popPendingContact(publicKey: newContact.id)
}

// Clear all pending
session.flushPendingContacts()
```

## Routing and Paths

### Check Routing Mode

```swift
if contact.isFloodPath {
    print("Using flood routing (broadcast)")
} else {
    print("Using direct path (\(contact.outPathLength) hops)")
}
```

### Reset Path

Force re-discovery of routing path:

```swift
try await session.resetPath(publicKey: contact.publicKey)
```

### Change Path

Update a contact's routing:

```swift
try await session.changeContactPath(contact, path: newPathData)

// Reset to flood
try await session.changeContactPath(contact, path: Data())
```

## Contact Flags

Modify contact capabilities:

```swift
try await session.changeContactFlags(contact, flags: newFlags)
```

## Sharing Contacts

Broadcast a contact's info to nearby nodes:

```swift
try await session.shareContact(publicKey: contact.publicKey)
```

## Exporting/Importing

### Export as URI

```swift
let uri = try await session.exportContact(publicKey: contact.publicKey)
// Share via QR code or text
```

### Import from Card Data

```swift
try await session.importContact(cardData: cardData)
```

## Auto-Update Contacts

Enable automatic refresh when advertisements are received:

```swift
session.setAutoUpdateContacts(true)
```
