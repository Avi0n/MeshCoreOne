import Foundation
import MeshCore
import os

// MARK: - Contact Service Errors

public enum ContactServiceError: Error, Sendable, LocalizedError {
  case notConnected
  case sendFailed
  case invalidResponse
  case syncInterrupted
  case contactNotFound
  case contactTableFull
  case shareContactUnavailable
  case sessionError(MeshCoreError)

  public var errorDescription: String? {
    switch self {
    case .notConnected:
      "Not connected to radio"
    case .sendFailed:
      "Failed to send message"
    case .invalidResponse:
      "Invalid response from device"
    case .syncInterrupted:
      "Sync was interrupted"
    case .contactNotFound:
      "Contact not found on device"
    case .contactTableFull:
      "Device node list is full"
    case .shareContactUnavailable:
      "Unable to share node. The node's advertisement may be missing or too old."
    case let .sessionError(error):
      error.localizedDescription
    }
  }
}

/// Reason for contact cleanup (deletion or blocking)
enum ContactCleanupReason {
  case deleted
  case blocked
  case unblocked
}

// MARK: - Sync Result

/// Result of a contact sync operation
public struct ContactSyncResult: Sendable {
  public let contactsReceived: Int
  public let lastSyncTimestamp: UInt32
  public let isIncremental: Bool

  public init(contactsReceived: Int, lastSyncTimestamp: UInt32, isIncremental: Bool) {
    self.contactsReceived = contactsReceived
    self.lastSyncTimestamp = lastSyncTimestamp
    self.isIncremental = isIncremental
  }
}

// MARK: - Contact Service

/// Service for managing mesh network contacts.
/// Handles contact discovery, sync, add/update/remove operations.
public actor ContactService {
  // MARK: - Properties

  let session: any MeshCoreSessionProtocol
  let dataStore: any PersistenceStoreProtocol
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "ContactService")

  static let deletionUpdateBatchSize = 20

  /// Sync coordinator for UI refresh notifications.
  /// Injected by `ServiceContainer` at construction.
  private weak var syncCoordinator: SyncCoordinator?

  /// Runs the cross-service cleanup chain when a contact is deleted, blocked, or unblocked.
  /// Injected by `ServiceContainer` at construction.
  private let cleanupCoordinator: (any ContactCleanupHandling)?

  /// One gate for contact sync and commands that read or write a radio contact.
  /// A cancelled waiter leaves without a command. Firmware can still change a contact between the fresh read and the update.
  private let contactOperationSerializer = RequestResponseSerializer()

  /// Multicast broadcaster for sync progress and node-deletion events.
  /// Consumers subscribe via `events()`; finished by `ServiceContainer.tearDown()`.
  private nonisolated let eventBroadcaster = EventBroadcaster<ContactServiceEvent>()

  // MARK: - Initialization

  init(
    session: any MeshCoreSessionProtocol,
    dataStore: any PersistenceStoreProtocol,
    syncCoordinator: SyncCoordinator?,
    cleanupCoordinator: (any ContactCleanupHandling)?
  ) {
    self.session = session
    self.dataStore = dataStore
    self.syncCoordinator = syncCoordinator
    self.cleanupCoordinator = cleanupCoordinator
  }

  // MARK: - Events

  /// Returns a fresh stream of contact service events. Registration is
  /// synchronous, so events yielded after this call returns are never
  /// dropped. Consumers re-subscribe per connection because the owning
  /// `ServiceContainer` is rebuilt.
  public nonisolated func events() -> AsyncStream<ContactServiceEvent> {
    eventBroadcaster.subscribe()
  }

  /// Ends every `events()` subscriber's for-await loop. Called by
  /// `ServiceContainer.tearDown()` so consumer tasks release their service
  /// references.
  nonisolated func finishEvents() {
    eventBroadcaster.finish()
  }

  // MARK: - Configuration

  /// Whether a sync coordinator was injected at construction.
  var hasSyncCoordinatorWired: Bool {
    syncCoordinator != nil
  }

  /// Whether a cleanup coordinator was injected at construction.
  var hasCleanupCoordinatorWired: Bool {
    cleanupCoordinator != nil
  }

  // MARK: - Contact Sync

  /// Sync all contacts from device
  /// - Parameters:
  ///   - radioID: The device to sync from
  ///   - since: Optional date for incremental sync (only contacts modified after this time)
  /// - Returns: Sync result with count and timestamp
  public func syncContacts(radioID: UUID, since: Date? = nil) async throws -> ContactSyncResult {
    try await withContactOperation {
      do {
        let fetchResult = try await session.getContactsReportingTotal(since: since)
        let meshContacts = fetchResult.contacts

        eventBroadcaster.yield(.syncProgress(received: 0, total: meshContacts.count))

        // Build set of public keys from device for cleanup
        let devicePublicKeys = Set(meshContacts.map(\.publicKey))

        // Persist every received contact in a single transaction rather than one
        // commit per contact; the per-item save was redundant overhead on every sync.
        let frames = try await reconcileBlockedRemoteCLI(radioID: radioID, contacts: meshContacts)
        let receivedCount = try await dataStore.batchSaveContacts(radioID: radioID, from: frames)

        let lastTimestamp = meshContacts
          .map { UInt32($0.lastModified.timeIntervalSince1970) }
          .max() ?? 0

        eventBroadcaster.yield(.syncProgress(received: receivedCount, total: meshContacts.count))

        // On full sync, remove local contacts that no longer exist on device.
        if since == nil {
          try await pruneOrphans(
            radioID: radioID,
            devicePublicKeys: devicePublicKeys,
            reportedTotal: fetchResult.reportedTotal
          )
        }

        return ContactSyncResult(
          contactsReceived: receivedCount,
          lastSyncTimestamp: lastTimestamp,
          isIncremental: since != nil
        )
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }
    }
  }

  /// Removes local contacts that a full fetch proves are gone from the device.
  ///
  /// Prunes only on a complete snapshot: received count must meet the
  /// `contactsStart` total. A truncated stream leaves keys missing without a
  /// real device deletion, so those must not prune. A genuine shrink lowers the
  /// reported total in step, so a complete-but-smaller reply still prunes.
  /// Both counts include the ZephCore V-contact when firmware streams it.
  ///
  /// A `nil` `reportedTotal` means the reply carried no `contactsStart` header,
  /// so the device total is unknown and the snapshot cannot be proven complete;
  /// the prune skips.
  ///
  /// Without a usable self public key the V-contact cannot be identified, so the
  /// prune skips rather than risk deleting a local V-contact row that firmware
  /// omitted while clock-deferred.
  private func pruneOrphans(radioID: UUID, devicePublicKeys: Set<Data>, reportedTotal: Int?) async throws {
    guard let reportedTotal else {
      logger.notice("Full sync prune skipped: device sent no contact total (missing contactsStart header)")
      return
    }
    guard devicePublicKeys.count >= reportedTotal else {
      logger.notice(
        "Full sync prune skipped: received \(devicePublicKeys.count) of \(reportedTotal) reported device contacts (incomplete snapshot)"
      )
      return
    }
    guard let selfPublicKey = try? await dataStore.fetchDevice(radioID: radioID)?.publicKey,
          selfPublicKey.count == ProtocolLimits.publicKeySize else {
      logger.notice("Full sync prune skipped: self public key unavailable or invalid, cannot exclude the V-contact")
      return
    }

    let localContacts = try await dataStore.fetchContacts(radioID: radioID)
    let orphans = localContacts.filter { contact in
      guard !devicePublicKeys.contains(contact.publicKey) else { return false }
      if VContactIdentity.isVContact(publicKey: contact.publicKey, selfPublicKey: selfPublicKey) {
        return false
      }
      return true
    }
    if !orphans.isEmpty {
      logger.notice("Full sync prune: \(orphans.count) local contact(s) not found on device (device has \(devicePublicKeys.count), local has \(localContacts.count))")
    }
    var deletedIDs: Set<UUID> = []
    do {
      for localContact in orphans {
        try Task.checkCancellation()
        let keyPrefix = localContact.publicKey.prefix(4).map { String(format: "%02x", $0) }.joined()
        logger.notice("Full sync prune: deleting '\(localContact.name)' [\(keyPrefix)…] (favorite=\(localContact.isFavorite), type=\(localContact.typeRawValue), lastModified=\(localContact.lastModified))")
        try await dataStore.deleteContact(id: localContact.id)
        deletedIDs.insert(localContact.id)
        await cleanupCoordinator?.handleDeletion(contactID: localContact.id, publicKey: localContact.publicKey)
        await flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: false)
      }
    } catch {
      await flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: true)
      throw error
    }
    await flushDeletionUpdatesIfNeeded(contactIDs: &deletedIDs, force: true)
  }

  /// Full contact sync for a user-initiated refresh.
  ///
  /// Atomically waits out an advert-driven delta sync and claims the manual
  /// refresh flag on the coordinator. Separate wait and claim hops leave a
  /// window where a delta can take the claim after the wait returns and before
  /// the flag is set; both publish `syncProgress` on the same event stream, so
  /// that race jumps the pull-to-refresh counter. Callers still call
  /// `AdvertisementService.setSyncingContacts(true)` first so no new delta
  /// starts before this claim runs.
  ///
  /// - Parameter radioID: The device to sync from
  /// - Returns: Sync result with count and timestamp
  public func syncContactsForRefresh(radioID: UUID) async throws -> ContactSyncResult {
    do {
      try await syncCoordinator?.claimManualContactSync()
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      // Wait timed out while an advert delta still held the claim. Do not
      // proceed (races progress) and do not silent-skip: surface so the spinner stops.
      throw ContactServiceError.syncInterrupted
    }
    do {
      let result = try await syncContacts(radioID: radioID)
      await syncCoordinator?.setManualContactSyncActive(false)
      return result
    } catch {
      await syncCoordinator?.setManualContactSyncActive(false)
      throw error
    }
  }

  // MARK: - Get Contact

  /// Get a specific contact by public key from local database
  /// - Parameters:
  ///   - radioID: The device ID
  ///   - publicKey: The contact's 32-byte public key
  /// - Returns: The contact if found
  public func getContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
    try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey)
  }

  // MARK: - Add/Update Contact

  /// Add or update a contact on the device
  /// - Parameters:
  ///   - radioID: The device ID captured when the command was sent
  ///   - contact: The contact to add/update
  ///   - radioIDAtInsert: Read after the radio command returns. The local row uses that id.
  public func addOrUpdateContact(
    radioID: UUID,
    contact: ContactFrame,
    radioIDAtInsert: (@MainActor () -> UUID?)? = nil
  ) async throws {
    try await withContactOperation {
      try await addOrUpdateContactOwned(
        radioID: radioID,
        contact: contact,
        radioIDAtInsert: radioIDAtInsert
      )
    }
  }

  private func addOrUpdateContactOwned(
    radioID: UUID,
    contact: ContactFrame,
    radioIDAtInsert: (@MainActor () -> UUID?)?
  ) async throws {
    do {
      let meshContact = contact.toMeshContact()
      try await session.addContact(meshContact)

      let partition: UUID
      if let radioIDAtInsert {
        guard let live = await radioIDAtInsert() else { return }
        partition = live
      } else {
        partition = radioID
      }

      // Save to local database
      _ = try await dataStore.saveContact(radioID: partition, from: contact)

      // Notify UI to refresh contacts list
      await syncCoordinator?.notifyContactsChanged()
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.tableFull.rawValue {
        throw ContactServiceError.contactTableFull
      }
      throw ContactServiceError.sessionError(error)
    }
  }

  // MARK: - Remove Contact

  /// Remove a contact from the device
  /// - Parameters:
  ///   - radioID: The device ID
  ///   - publicKey: The contact's 32-byte public key
  ///
  /// ZephCore V-contact remove is disabled: no `CMD_REMOVE` (which would turn `v.contact` off),
  /// no local wipe, and no `.nodeDeleted` storage-full clear.
  public func removeContact(radioID: UUID, publicKey: Data) async throws {
    _ = try await removeContact(radioID: radioID, publicKey: publicKey, deferDeletionUpdates: false)
  }

  func removeContact(radioID: UUID, publicKey: Data, deferDeletionUpdates: Bool) async throws -> UUID? {
    try await withContactOperation {
      try await removeContactOwned(
        radioID: radioID,
        publicKey: publicKey,
        deferDeletionUpdates: deferDeletionUpdates
      )
    }
  }

  private func removeContactOwned(
    radioID: UUID,
    publicKey: Data,
    deferDeletionUpdates: Bool
  ) async throws -> UUID? {
    if await isProtectedVContact(radioID: radioID, publicKey: publicKey) {
      logger.info("removeContact ignored for ZephCore V-contact (remove disabled)")
      return nil
    }

    do {
      try await session.removeContact(publicKey: publicKey)

      var deletedID: UUID?
      if let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) {
        let contactID = contact.id

        try await dataStore.deleteContact(id: contactID)

        deletedID = contactID
        await handleContactDeletion(contactID: contactID, publicKey: publicKey, deferUpdates: deferDeletionUpdates)
      }

      // Notify that a node was deleted (for clearing storage full flag)
      eventBroadcaster.yield(.nodeDeleted)

      // Notify UI to refresh contacts list
      if !deferDeletionUpdates { await syncCoordinator?.notifyContactsChanged() }
      return deletedID
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue {
        throw ContactServiceError.contactNotFound
      }
      throw ContactServiceError.sessionError(error)
    }
  }

  /// Remove a contact's local data and run the full cleanup chain without contacting the device.
  /// Use when the device reports the contact doesn't exist but local data remains.
  ///
  /// ZephCore V-contact remove is disabled (same policy as ``removeContact``).
  public func removeLocalContact(contactID: UUID, publicKey: Data) async throws {
    _ = try await removeLocalContact(contactID: contactID, publicKey: publicKey, deferDeletionUpdates: false)
  }

  func removeLocalContact(contactID: UUID, publicKey: Data, deferDeletionUpdates: Bool) async throws -> UUID? {
    try await withContactOperation {
      try await removeLocalContactOwned(
        contactID: contactID,
        publicKey: publicKey,
        deferDeletionUpdates: deferDeletionUpdates
      )
    }
  }

  private func removeLocalContactOwned(
    contactID: UUID,
    publicKey: Data,
    deferDeletionUpdates: Bool
  ) async throws -> UUID? {
    if let contact = try? await dataStore.fetchContact(id: contactID),
       await isProtectedVContact(radioID: contact.radioID, publicKey: publicKey) {
      logger.info("removeLocalContact ignored for ZephCore V-contact (remove disabled)")
      return nil
    }

    try await dataStore.deleteContact(id: contactID)
    await handleContactDeletion(contactID: contactID, publicKey: publicKey, deferUpdates: deferDeletionUpdates)
    eventBroadcaster.yield(.nodeDeleted)
    if !deferDeletionUpdates { await syncCoordinator?.notifyContactsChanged() }
    return contactID
  }

  private func handleContactDeletion(contactID: UUID, publicKey: Data, deferUpdates: Bool) async {
    if deferUpdates {
      await cleanupCoordinator?.handleDeletion(contactID: contactID, publicKey: publicKey)
    } else {
      await cleanupCoordinator?.handleCleanup(contactID: contactID, reason: .deleted, publicKey: publicKey)
    }
  }

  func flushDeletionUpdatesIfNeeded(contactIDs: inout Set<UUID>, force: Bool) async {
    guard !contactIDs.isEmpty, force || contactIDs.count >= Self.deletionUpdateBatchSize else { return }
    let deletedIDs = contactIDs
    contactIDs.removeAll()
    await cleanupCoordinator?.flushDeletionUpdates(contactIDs: deletedIDs)
    await syncCoordinator?.notifyContactsChanged()
  }

  /// Whether `publicKey` is the ZephCore V-contact for this radio (remove/prune protected).
  private func isProtectedVContact(radioID: UUID, publicKey: Data) async -> Bool {
    guard let selfPublicKey = try? await dataStore.fetchDevice(radioID: radioID)?.publicKey else {
      return false
    }
    return VContactIdentity.isVContact(publicKey: publicKey, selfPublicKey: selfPublicKey)
  }

  /// Clears all messages for a direct conversation without deleting the contact.
  /// Preserves `lastMessageDate` so the now-empty conversation stays in the chats list
  /// (showing "No messages"), unlike delete-conversation which nils the date. Also clears
  /// both unread counters and notifies observers so no stale badge or preview survives.
  public func clearContactMessages(contactID: UUID) async throws {
    try await dataStore.deleteMessagesForContact(contactID: contactID)
    try await dataStore.clearUnreadCount(contactID: contactID)
    try await dataStore.clearUnreadMentionCount(contactID: contactID)
    await syncCoordinator?.notifyConversationsChanged()
  }

  // MARK: - Reset Path

  /// Reset the path for a contact (force rediscovery)
  /// - Parameters:
  ///   - radioID: The device ID
  ///   - publicKey: The contact's 32-byte public key
  public func resetPath(radioID: UUID, publicKey: Data) async throws {
    try await withContactOperation {
      try await resetPathOwned(radioID: radioID, publicKey: publicKey)
    }
  }

  private func resetPathOwned(radioID: UUID, publicKey: Data) async throws {
    do {
      try await session.resetPath(publicKey: publicKey)
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue {
        throw ContactServiceError.contactNotFound
      }
      throw ContactServiceError.sessionError(error)
    }
    _ = try await dataStore.updateContactRoute(
      radioID: radioID,
      publicKey: publicKey,
      outPathLength: PacketBuilder.floodPathSentinel,
      outPath: Data()
    )
  }

  // MARK: - Path Discovery

  /// Send a path discovery request to find optimal route to contact
  /// - Parameters:
  ///   - radioID: The device ID
  ///   - publicKey: The contact's 32-byte public key
  /// - Returns: MessageSentInfo containing the estimated timeout from firmware
  public func sendPathDiscovery(radioID: UUID, publicKey: Data) async throws -> MessageSentInfo {
    do {
      return try await session.sendPathDiscovery(to: publicKey)
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue {
        throw ContactServiceError.contactNotFound
      }
      throw ContactServiceError.sessionError(error)
    }
  }

  // MARK: - Set Path

  /// Set a specific path for a contact
  /// - Parameters:
  ///   - radioID: The device ID
  ///   - publicKey: The contact's 32-byte public key
  ///   - path: The path data (repeater hashes)
  ///   - pathLength: Encoded path length byte (0xFF for flood, 0 for direct, >0 for routed)
  public func setPath(radioID: UUID, publicKey: Data, path: Data, pathLength: UInt8) async throws {
    try await withContactOperation {
      try await setPathOwned(radioID: radioID, publicKey: publicKey, path: path, pathLength: pathLength)
    }
  }

  /// Writes the caller's path onto the current radio contact and mirrors only that path locally.
  private func setPathOwned(
    radioID: UUID,
    publicKey: Data,
    path: Data,
    pathLength: UInt8
  ) async throws {
    guard try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) != nil else {
      throw ContactServiceError.contactNotFound
    }
    let radio: MeshContact
    do {
      guard let fetched = try await session.getContact(publicKey: publicKey) else {
        throw ContactServiceError.contactNotFound
      }
      radio = fetched
    } catch let error as ContactServiceError {
      throw error
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }

    let updated = MeshContact(
      id: radio.id,
      publicKey: radio.publicKey,
      type: radio.type,
      typeRawValue: radio.typeRawValue,
      flags: radio.flags,
      outPathLength: pathLength,
      outPath: path,
      advertisedName: radio.advertisedName,
      lastAdvertisement: radio.lastAdvertisement,
      latitude: radio.latitude,
      longitude: radio.longitude,
      lastModified: radio.lastModified
    )
    do {
      try await session.addContact(updated)
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.tableFull.rawValue {
        throw ContactServiceError.contactTableFull
      }
      throw ContactServiceError.sessionError(error)
    }
    guard try await dataStore.updateContactRoute(
      radioID: radioID,
      publicKey: publicKey,
      outPathLength: pathLength,
      outPath: path
    ) != nil else {
      throw ContactServiceError.contactNotFound
    }
    await syncCoordinator?.notifyContactsChanged()
  }

  // MARK: - Share Contact

  /// Share a contact via zero-hop broadcast
  /// - Parameter publicKey: The contact's 32-byte public key to share
  public func shareContact(publicKey: Data) async throws {
    do {
      try await session.shareContact(publicKey: publicKey)
    } catch let error as MeshCoreError {
      if case let .deviceError(code) = error, code == ProtocolError.tableFull.rawValue {
        throw ContactServiceError.shareContactUnavailable
      }
      if case let .deviceError(code) = error, code == ProtocolError.notFound.rawValue {
        throw ContactServiceError.contactNotFound
      }
      throw ContactServiceError.sessionError(error)
    }
  }

  // MARK: - Export/Import Contact

  /// Export a contact to a shareable URI (legacy firmware call)
  /// - Parameter publicKey: The contact's 32-byte public key (nil for self)
  /// - Returns: Contact URI string
  @available(*, deprecated, message: "Use exportContactURI(name:publicKey:type:) instead")
  public func exportContact(publicKey: Data? = nil) async throws -> String {
    do {
      return try await session.exportContact(publicKey: publicKey)
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }
  }

  private static let contactURIScheme = "meshcore"
  private static let contactURIHost = "contact"
  private static let contactURIPath = "/add"
  private static let contactURINameKey = "name"
  private static let contactURIPublicKeyKey = "public_key"
  private static let contactURITypeKey = "type"

  /// Build a shareable contact URI from contact information
  /// - Parameters:
  ///   - name: The contact's advertised name
  ///   - publicKey: The contact's 32-byte public key
  ///   - type: The contact type (chat, repeater, room)
  /// - Returns: Contact URI string in format: meshcore://contact/add?name=...&public_key=...&type=...
  public static func exportContactURI(name: String, publicKey: Data, type: ContactType) -> String {
    // Encode via MeshCoreURIQuery so `&`/`=` in the name cannot inject public_key/type.
    var components = URLComponents()
    components.scheme = contactURIScheme
    components.host = contactURIHost
    components.path = contactURIPath
    components.percentEncodedQueryItems = MeshCoreURIQuery.percentEncodedQueryItems(from: [
      URLQueryItem(name: contactURINameKey, value: name),
      URLQueryItem(name: contactURIPublicKeyKey, value: publicKey.uppercaseHexString()),
      URLQueryItem(name: contactURITypeKey, value: String(type.rawValue))
    ])
    return components.url?.absoluteString ?? ""
  }

  /// Import a contact from card data
  /// - Parameter cardData: The contact card data
  public func importContact(cardData: Data) async throws {
    try await withContactOperation {
      do {
        try await session.importContact(cardData: cardData)
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }
    }
  }

  // MARK: - Local Database Operations

  /// Get all contacts for a device from local database
  public func getContacts(radioID: UUID) async throws -> [ContactDTO] {
    try await dataStore.fetchContacts(radioID: radioID)
  }

  /// Get conversations (contacts with messages) from local database
  public func getConversations(radioID: UUID) async throws -> [ContactDTO] {
    try await dataStore.fetchConversations(radioID: radioID)
  }

  /// Get a contact by ID from local database
  public func getContactByID(_ id: UUID) async throws -> ContactDTO? {
    try await dataStore.fetchContact(id: id)
  }

  /// Update local contact preferences (nickname, blocked, favorite).
  /// `nickname`: `nil` leaves the existing nickname unchanged; an empty or
  /// whitespace-only string clears it. A non-empty value is trimmed and stored.
  public func updateContactPreferences(
    contactID: UUID,
    nickname: String? = nil,
    isBlocked: Bool? = nil,
    isFavorite: Bool? = nil
  ) async throws {
    try await withContactOperation {
      try await updateContactPreferencesOwned(
        contactID: contactID,
        nickname: nickname,
        isBlocked: isBlocked,
        isFavorite: isFavorite
      )
    }
  }

  private func updateContactPreferencesOwned(
    contactID: UUID,
    nickname: String?,
    isBlocked: Bool?,
    isFavorite: Bool?
  ) async throws {
    guard let existing = try await dataStore.fetchContact(id: contactID) else {
      throw ContactServiceError.contactNotFound
    }

    let nicknameUpdate: ContactNicknameUpdate
    if let nickname {
      let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
      nicknameUpdate = trimmed.isEmpty ? .clear : .set(trimmed)
    } else {
      nicknameUpdate = .unchanged
    }

    let becomingBlocked = isBlocked == true && !existing.isBlocked
    if becomingBlocked {
      try await revokeRemoteCLIIfRadioAllows(publicKey: existing.publicKey)
    }

    let commit: ContactPreferenceCommit
    do {
      commit = try await dataStore.updateContactLocalPreferences(
        contactID: contactID,
        nickname: nicknameUpdate,
        isBlocked: isBlocked,
        isFavorite: isFavorite,
        clearRemoteCLI: becomingBlocked
      )
    } catch let error as PersistenceStoreError {
      throw mapStoreError(error)
    }

    switch commit.blockTransition {
    case .blocked:
      await cleanupCoordinator?.handleCleanup(
        contactID: contactID,
        reason: .blocked,
        publicKey: commit.contact.publicKey
      )
    case .unblocked:
      await cleanupCoordinator?.handleCleanup(
        contactID: contactID,
        reason: .unblocked,
        publicKey: commit.contact.publicKey
      )
    case .none:
      break
    }
    await syncCoordinator?.notifyContactsChanged()
  }

  /// Updates OCV settings for a contact
  /// - Parameters:
  ///   - contactID: The contact's ID
  ///   - preset: The OCV preset name
  ///   - customArray: Custom OCV array string (for custom preset)
  public func updateContactOCVSettings(
    contactID: UUID,
    preset: String,
    customArray: String?
  ) async throws {
    do {
      _ = try await dataStore.updateContactOCVSettings(
        contactID: contactID,
        preset: preset,
        customArray: customArray
      )
    } catch let error as PersistenceStoreError {
      throw mapStoreError(error)
    }
  }

  /// Updates a contact's locally stored profile picture.
  /// - Parameters:
  ///   - contactID: The contact's ID
  ///   - imageData: Compressed JPEG data for the new avatar, or `nil` to remove it
  public func updateContactAvatar(contactID: UUID, imageData: Data?) async throws {
    do {
      _ = try await dataStore.updateContactAvatar(contactID: contactID, imageData: imageData)
    } catch let error as PersistenceStoreError {
      throw mapStoreError(error)
    }
    await syncCoordinator?.notifyContactsChanged()
  }

  // MARK: - Device Favorite Sync

  /// Sets a contact's favorite status on the device and updates local storage.
  ///
  /// This method updates the device's contact flags (bit 0 = favorite), waits for
  /// confirmation, then updates the local SwiftData contact.
  ///
  /// - Parameters:
  ///   - contactID: The contact's UUID.
  ///   - isFavorite: Whether to mark the contact as a favorite.
  /// - Throws: `ContactServiceError` if the device update fails.
  @discardableResult
  public func setContactFavorite(_ contactID: UUID, isFavorite: Bool) async throws -> ContactDTO {
    try await withContactOperation {
      guard let existing = try await dataStore.fetchContact(id: contactID) else {
        throw ContactServiceError.contactNotFound
      }
      try await writeOwnedRadioFlags(
        publicKey: existing.publicKey,
        mask: ContactFlags.favorite.rawValue,
        granted: isFavorite
      )
      do {
        guard let confirmed = try await session.getContact(publicKey: existing.publicKey) else {
          throw ContactServiceError.contactNotFound
        }
        guard confirmed.flags.contains(.favorite) == isFavorite else {
          throw ContactServiceError.invalidResponse
        }
      } catch let error as ContactServiceError {
        throw error
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }

      let saved: ContactDTO
      do {
        saved = try await dataStore.updateContactFavorite(contactID: contactID, isFavorite: isFavorite)
      } catch let error as PersistenceStoreError {
        throw mapStoreError(error)
      }
      await syncCoordinator?.notifyContactsChanged()
      return saved
    }
  }

  /// Acquires the contact operation gate, runs `operation` on this task, and releases on every exit.
  /// A cancelled waiter may sit until it acquires, then leaves without reading the radio or the store.
  private func withContactOperation<T: Sendable>(
    _ operation: () async throws -> T
  ) async throws -> T {
    await contactOperationSerializer.acquire()
    do {
      try Task.checkCancellation()
      let value = try await operation()
      await contactOperationSerializer.release()
      return value
    } catch {
      await contactOperationSerializer.release()
      throw error
    }
  }

  #if DEBUG
    func contactOperationWaiterCountForTesting() async -> Int {
      await contactOperationSerializer.waiterCountForTest
    }
  #endif

  /// Applies `mask` on the radio's current flags and writes that contact back.
  /// Carried-forward fields come from the radio record, including its raw type and path.
  private func writeOwnedRadioFlags(publicKey: Data, mask: UInt8, granted: Bool) async throws {
    let radio: MeshContact
    do {
      guard let fetched = try await session.getContact(publicKey: publicKey) else {
        throw ContactServiceError.contactNotFound
      }
      radio = fetched
    } catch let error as ContactServiceError {
      throw error
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }
    let newFlags: UInt8 = granted
      ? radio.flags.rawValue | mask
      : radio.flags.rawValue & ~mask
    do {
      try await session.changeContactFlags(radio, flags: ContactFlags(rawValue: newFlags))
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }
  }

  /// Clears remote CLI on the radio when the live record allows it.
  /// A missing radio contact is not recreated; the local block can still proceed.
  private func revokeRemoteCLIIfRadioAllows(publicKey: Data) async throws {
    let radio: MeshContact?
    do {
      radio = try await session.getContact(publicKey: publicKey)
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }
    guard let radio, radio.flags.contains(.remoteCLIAllowed) else { return }
    let cleared = radio.flags.rawValue & ~ContactFlags.remoteCLIAllowed.rawValue
    do {
      try await session.changeContactFlags(radio, flags: ContactFlags(rawValue: cleared))
    } catch let error as MeshCoreError {
      throw ContactServiceError.sessionError(error)
    }
  }

  private func mapStoreError(_ error: PersistenceStoreError) -> Error {
    switch error {
    case .contactNotFound:
      ContactServiceError.contactNotFound
    default:
      error
    }
  }

  // MARK: - Telemetry Permissions

  /// Whether a contact has telemetry permission flags set (bits 1-3).
  public static func hasTelemetryPermissions(flags: UInt8) -> Bool {
    (flags & 0x0E) != 0
  }

  /// Sets telemetry permission flags on a contact's device record.
  /// Bits 1-3 of contact.flags control base/location/environment permissions.
  /// Bit 0 (favourite) is preserved.
  ///
  /// - Parameters:
  ///   - contactID: The contact's UUID.
  ///   - granted: Whether to grant telemetry permissions.
  /// - Throws: `ContactServiceError` if the device update fails.
  public func setTelemetryPermissions(_ contactID: UUID, granted: Bool) async throws {
    try await withContactOperation {
      guard let existing = try await dataStore.fetchContact(id: contactID) else {
        throw ContactServiceError.contactNotFound
      }
      try await writeOwnedRadioFlags(
        publicKey: existing.publicKey,
        mask: ContactFlags.telemetryAll.rawValue,
        granted: granted
      )
      do {
        _ = try await dataStore.updateContactTelemetryPermissions(contactID: contactID, granted: granted)
      } catch let error as PersistenceStoreError {
        throw mapStoreError(error)
      }
    }
  }

  // MARK: - Remote CLI Permission

  /// Whether a contact may send CLI commands to this radio over the mesh (bit 4).
  public static func hasRemoteCLIAllowed(flags: UInt8) -> Bool {
    ContactFlags(rawValue: flags).contains(.remoteCLIAllowed)
  }

  /// Grants or revokes remote CLI on a contact. Other flag bits are preserved.
  public func setRemoteCLIAllowed(_ contactID: UUID, granted: Bool) async throws {
    try await withContactOperation {
      guard let existing = try await dataStore.fetchContact(id: contactID) else {
        throw ContactServiceError.contactNotFound
      }
      try await writeOwnedRadioFlags(
        publicKey: existing.publicKey,
        mask: ContactFlags.remoteCLIAllowed.rawValue,
        granted: granted
      )
      do {
        _ = try await dataStore.updateContactRemoteCLIPermission(contactID: contactID, granted: granted)
      } catch let error as PersistenceStoreError {
        throw mapStoreError(error)
      }
    }
  }

  // MARK: - Favorites Migration

  private static let favoritesMigrationKey = "hasMigratedContactFavorites"

  /// Migrates existing app favorites to device flags (one-time operation).
  ///
  /// On first run after upgrade, this merges app favorites with device favorites:
  /// any contact that is favorite in either location becomes favorite in both.
  /// After migration, device wins for future syncs.
  ///
  /// - Parameter radioID: The connected device's UUID.
  /// - Returns: Number of contacts migrated to device.
  @discardableResult
  public func migrateAppFavoritesToDevice(radioID: UUID) async throws -> Int {
    // Check if already migrated
    if UserDefaults.standard.bool(forKey: Self.favoritesMigrationKey) {
      return 0
    }

    logger.info("Starting favorites migration to device")

    // Find contacts that are favorite in app but not on device
    let contacts = try await dataStore.fetchContacts(radioID: radioID)
    let toMigrate = contacts.filter { contact in
      contact.isFavorite && (contact.flags & 0x01) == 0
    }

    if toMigrate.isEmpty {
      logger.info("No favorites to migrate, marking complete")
      UserDefaults.standard.set(true, forKey: Self.favoritesMigrationKey)
      return 0
    }

    logger.info("Migrating \(toMigrate.count) favorites to device")

    var migratedCount = 0
    for contact in toMigrate {
      do {
        try await setContactFavorite(contact.id, isFavorite: true)
        migratedCount += 1
      } catch {
        logger.warning("Failed to migrate favorite for \(contact.name): \(error)")
        // Continue with other contacts, will retry on next connect
      }
    }

    // Only mark complete if all were migrated
    if migratedCount == toMigrate.count {
      UserDefaults.standard.set(true, forKey: Self.favoritesMigrationKey)
      logger.info("Favorites migration complete: \(migratedCount) contacts")
    } else {
      logger.warning("Partial migration: \(migratedCount)/\(toMigrate.count), will retry")
    }

    return migratedCount
  }

  /// Pushes a local contact onto a radio that does not have it, using flood routing.
  /// An existing radio contact is left alone so a stale local frame cannot replace it.
  public func healAbsentRadioContact(radioID: UUID, publicKey: Data) async throws -> Bool {
    try await withContactOperation {
      let alreadyPresent: Bool
      do {
        alreadyPresent = try await session.getContact(publicKey: publicKey) != nil
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }
      if alreadyPresent { return false }

      guard let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
        throw ContactServiceError.contactNotFound
      }
      let frame = contact.floodedContactFrame(asOf: UInt32(Date().timeIntervalSince1970))
      do {
        try await session.addContact(frame.toMeshContact())
      } catch let error as MeshCoreError {
        if case let .deviceError(code) = error, code == ProtocolError.tableFull.rawValue {
          throw ContactServiceError.contactTableFull
        }
        throw ContactServiceError.sessionError(error)
      }
      _ = try await dataStore.updateContactRoute(
        radioID: radioID,
        publicKey: publicKey,
        outPathLength: PacketBuilder.floodPathSentinel,
        outPath: Data()
      )
      return true
    }
  }

  /// Explicit contact import. The supplied record replaces the radio contact and the
  /// local radio fields. A local save failure does not hide a successful radio write.
  public func importRadioContact(radioID: UUID, contact: MeshContact) async throws {
    try await withContactOperation {
      do {
        try await session.addContact(contact)
      } catch let error as MeshCoreError {
        if case let .deviceError(code) = error, code == ProtocolError.tableFull.rawValue {
          throw ContactServiceError.contactTableFull
        }
        throw ContactServiceError.sessionError(error)
      }
      do {
        _ = try await dataStore.saveContact(radioID: radioID, from: contact.toContactFrame())
      } catch {
        logger.error(
          "Contact added to device but local save failed; next sync will reconcile: \(error.localizedDescription)"
        )
      }
    }
  }

  /// Reads the radio contact and stores only its out path on the existing local row.
  public func mirrorObservedRoute(radioID: UUID, publicKey: Data) async throws -> UInt8? {
    try await withContactOperation {
      let radio: MeshContact?
      do {
        radio = try await session.getContact(publicKey: publicKey)
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }
      guard let radio else { return nil }
      _ = try await dataStore.updateContactRoute(
        radioID: radioID,
        publicKey: publicKey,
        outPathLength: radio.outPathLength,
        outPath: radio.outPath
      )
      return radio.outPathLength
    }
  }

  /// One region target: fresh radio contact when one exists, otherwise the anonymous
  /// target. A local row whose radio contact is gone is not written back.
  func ownedRegionQuery(
    target: MeshContact,
    radioID: UUID,
    query: @Sendable (MeshContact) async throws -> [String]
  ) async throws -> [String] {
    try await withContactOperation {
      let radio: MeshContact?
      do {
        radio = try await session.getContact(publicKey: target.publicKey)
      } catch let error as MeshCoreError {
        throw ContactServiceError.sessionError(error)
      }
      if let radio {
        return try await query(radio)
      }
      let hasLocal = try await dataStore.fetchContact(radioID: radioID, publicKey: target.publicKey) != nil
      if hasLocal {
        throw ContactServiceError.contactNotFound
      }
      return try await query(target)
    }
  }
}

// MARK: - ContactServiceProtocol Conformance

extension ContactService: ContactServiceProtocol {
  // Already implements syncContacts(radioID:since:) -> ContactSyncResult
}

extension ContactService: ContactRadioOwning {}

// MARK: - MeshContact Extensions

extension MeshContact {
  /// Converts a MeshContact to a ContactFrame for persistence
  func toContactFrame() -> ContactFrame {
    ContactFrame(
      publicKey: publicKey,
      type: type,
      typeRawValue: typeRawValue,
      flags: flags.rawValue,
      outPathLength: outPathLength,
      outPath: outPath,
      name: advertisedName,
      lastAdvertTimestamp: UInt32(lastAdvertisement.timeIntervalSince1970),
      latitude: latitude,
      longitude: longitude,
      lastModified: UInt32(lastModified.timeIntervalSince1970)
    )
  }
}

// MARK: - ContactFrame Extensions

public extension ContactFrame {
  /// Converts a ContactFrame to a MeshContact for session operations
  func toMeshContact() -> MeshContact {
    MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: type,
      typeRawValue: typeRawValue,
      flags: ContactFlags(rawValue: flags),
      outPathLength: outPathLength,
      outPath: outPath,
      advertisedName: name,
      lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(lastAdvertTimestamp)),
      latitude: latitude,
      longitude: longitude,
      lastModified: Date(timeIntervalSince1970: TimeInterval(lastModified))
    )
  }
}
