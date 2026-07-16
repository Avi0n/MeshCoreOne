import Foundation
import MeshCore
import os

// MARK: - Advertisement Errors

public enum AdvertisementError: Error, Sendable {
  case notConnected
  case sendFailed
  case invalidResponse
  case sessionError(MeshCoreError)
}

extension AdvertisementError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .notConnected: "Not connected to device."
    case .sendFailed: "Failed to send advertisement."
    case .invalidResponse: "Invalid response from device."
    case let .sessionError(e): e.localizedDescription
    }
  }
}

// MARK: - Advertisement Service

/// Service for managing device advertisements and discovery.
/// Handles sending self-advertisements and processing incoming adverts via MeshCore events.
public actor AdvertisementService {
  // MARK: - Properties

  private let logger = PersistentLogger(subsystem: "com.mc1", category: "Advertisement")

  /// Temporary end-to-end Discover trace. Filter by category "discover-trace"
  /// to follow a single advert from push receipt through persistence to the
  /// view reload. Remove once the "no new nodes after clear" report is closed.
  private let discoverTrace = PersistentLogger(subsystem: "com.mc1", category: "discover-trace")

  private let session: any AdvertisingSessionOps & SessionEventStreaming
  private let dataStore: any PersistenceStoreProtocol

  /// Task monitoring for events
  private var eventMonitorTask: Task<Void, Never>?
  private var currentRadioID: UUID?

  /// Whether contact fetches should be deferred (during sync)
  private var isSyncingContacts = false
  private var pendingUnknownContactKeys: Set<Data> = []

  /// Tracks the last overwrite-oldest deletion for correlating with the replacement contact.
  /// The device sends 0x8F (deleted) then shortly after an advert for the new contact.
  private var lastOverwriteDeletion: (name: String, pubKeyHex: String, time: Date)?

  /// Multicast broadcaster for advertisement and discovery events.
  /// Producers yield synchronously; consumers subscribe via `events()`.
  nonisolated let eventBroadcaster = EventBroadcaster<AdvertisementEvent>()

  // MARK: - Initialization

  public init(session: any AdvertisingSessionOps & SessionEventStreaming, dataStore: any PersistenceStoreProtocol) {
    self.session = session
    self.dataStore = dataStore
  }

  deinit {
    eventMonitorTask?.cancel()
  }

  // MARK: - Events

  /// Returns a fresh stream of advertisement and discovery events.
  /// Registration is synchronous, so events yielded after this call are
  /// never dropped. Consumers must re-subscribe per connection because the
  /// owning `ServiceContainer` is rebuilt on every connection.
  public nonisolated func events() -> AsyncStream<AdvertisementEvent> {
    eventBroadcaster.subscribe()
  }

  /// Ends every `events()` subscriber's for-await loop. Called by
  /// `ServiceContainer.tearDown()` so consumer tasks release the service
  /// references they hold.
  nonisolated func finishEvents() {
    eventBroadcaster.finish()
  }

  // MARK: - Event Monitoring

  /// Start monitoring MeshCore events for advertisement-related notifications
  public func startEventMonitoring(radioID: UUID) {
    eventMonitorTask?.cancel()
    currentRadioID = radioID

    eventMonitorTask = Task { [weak self] in
      guard let self else { return }
      let filter = EventFilter { event in
        switch event {
        case .advertisement, .newContact, .pathUpdate, .pathResponse,
             .traceData, .contactDeleted, .contactsFull:
          true
        case let .rxLogData(log) where log.payloadType == .trace:
          true
        default:
          false
        }
      }
      let events = await session.events(filter: filter)

      for await event in events {
        guard !Task.isCancelled else { break }
        await handleEvent(event, radioID: radioID)
      }
    }
  }

  /// Stop monitoring events
  public func stopEventMonitoring() {
    eventMonitorTask?.cancel()
    eventMonitorTask = nil
    currentRadioID = nil
  }

  /// Toggle deferred contact fetching during sync.
  public func setSyncingContacts(_ isSyncing: Bool) async {
    isSyncingContacts = isSyncing
    if !isSyncing {
      await fetchPendingUnknownContacts()
    }
  }

  /// Handle incoming MeshCore event
  private func handleEvent(_ event: MeshEvent, radioID: UUID) async {
    switch event {
    case let .advertisement(publicKey):
      await handleAdvertEvent(publicKey: publicKey, radioID: radioID)

    case let .newContact(contact):
      await handleNewAdvertEvent(contact: contact, radioID: radioID)

    case let .pathUpdate(publicKey):
      await handlePathUpdatedEvent(publicKey: publicKey, radioID: radioID)

    case let .pathResponse(result):
      await handlePathDiscoveryResponse(result: result, radioID: radioID)

    case let .traceData(traceInfo):
      await handleTraceData(traceInfo: traceInfo, radioID: radioID)

    case let .rxLogData(logData) where logData.payloadType == .trace:
      if logData.packetPayload.count >= 4, let snr = logData.snr {
        let tag = logData.packetPayload.readUInt32LE(at: 0)
        let remoteSnr: Double? = logData.pathNodes.last.map {
          Double(Int8(bitPattern: $0)) / 4.0
        }
        eventBroadcaster.yield(.traceSnrObserved(tag: tag, localSnr: snr, remoteSnr: remoteSnr, radioID: radioID))
      }

    case let .contactDeleted(publicKey):
      await handleContactDeletedEvent(publicKey: publicKey, radioID: radioID)

    case .contactsFull:
      await handleContactsFullEvent()

    default:
      break
    }
  }

  // MARK: - Send Advertisement

  /// Send self advertisement to the mesh network
  /// - Parameter flood: If true, sends flood advertisement (reaches all nodes).
  ///                   If false, sends zero-hop advertisement (direct only).
  public func sendSelfAdvertisement(flood: Bool) async throws {
    do {
      try await session.sendAdvertisement(flood: flood)
    } catch let error as MeshCoreError {
      throw AdvertisementError.sessionError(error)
    }
  }

  // MARK: - Update Node Name

  /// Set the node's advertised name
  /// - Parameter name: The name to advertise (max 31 characters)
  public func setAdvertName(_ name: String) async throws {
    do {
      try await session.setName(name)
    } catch let error as MeshCoreError {
      throw AdvertisementError.sessionError(error)
    }
  }

  // MARK: - Update Location

  /// Set the node's advertised GPS coordinates
  /// - Parameters:
  ///   - latitude: Latitude in degrees (-90 to 90)
  ///   - longitude: Longitude in degrees (-180 to 180)
  public func setAdvertLocation(latitude: Double, longitude: Double) async throws {
    do {
      try await session.setCoordinates(latitude: latitude, longitude: longitude)
    } catch let error as MeshCoreError {
      throw AdvertisementError.sessionError(error)
    }
  }

  // MARK: - Private Event Handlers

  /// Handle advertisement event - Existing contact updated
  private func handleAdvertEvent(publicKey: Data, radioID: UUID) async {
    let pubKeyHex = publicKey.map { String(format: "%02X", $0) }.joined()
    logger.debug("Advert event for \(pubKeyHex)")
    discoverTrace.info("B1 0x80 ADVERT received key=\(pubKeyHex)")

    let timestamp = UInt32(Date().timeIntervalSince1970)

    do {
      if let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) {
        // Create a modified version with updated timestamp
        let frame = ContactFrame(
          publicKey: contact.publicKey,
          type: contact.type,
          flags: contact.flags,
          outPathLength: contact.outPathLength,
          outPath: contact.outPath,
          name: contact.name,
          lastAdvertTimestamp: timestamp,
          latitude: contact.latitude,
          longitude: contact.longitude,
          lastModified: UInt32(Date().timeIntervalSince1970)
        )
        _ = try await dataStore.saveContact(radioID: radioID, from: frame)

        // Also track in DiscoveredNode for Discover page visibility
        do {
          let (_, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
          discoverTrace.info("B2 0x80 known-contact upsert key=\(pubKeyHex) isNew=\(isNew)")
        } catch {
          discoverTrace.error("B2 0x80 known-contact upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
        }

        // Notify UI of contact update
        eventBroadcaster.yield(.contactUpdated)
      } else {
        discoverTrace.info("B2 0x80 no local contact key=\(pubKeyHex) syncing=\(isSyncingContacts)")
        if isSyncingContacts {
          pendingUnknownContactKeys.insert(publicKey)
          logger.info("ADVERT received for unknown contact during sync - deferring fetch")
        } else {
          // Unknown contact - device has it but we don't (auto-add mode)
          // Fetch just this contact from device and notify
          logger.info("ADVERT received for unknown contact - fetching from device")
          do {
            if let meshContact = try await session.getContact(publicKey: publicKey) {
              let frame = meshContact.toContactFrame()
              let contactID = try await dataStore.saveContact(radioID: radioID, from: frame)

              // Also track in DiscoveredNode for Discover page visibility
              do {
                let (_, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
                discoverTrace.info("B2 0x80 getContact OK upsert key=\(pubKeyHex) isNew=\(isNew)")
              } catch {
                discoverTrace.error("B2 0x80 getContact-path upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
              }

              // Empty names pass through raw; NotificationService substitutes a localized fallback.
              let contactName = meshContact.advertisedName
              let contactType = meshContact.type
              eventBroadcaster.yield(.newContactDiscovered(name: contactName, contactID: contactID, contactType: contactType))

              // Correlate with recent overwrite-oldest deletion
              logOverwriteReplacementIfRecent(
                newContactName: contactName.isEmpty ? "Unknown Contact" : contactName,
                newContactType: contactType
              )
            } else {
              discoverTrace.notice("B2 0x80 getContact returned nil key=\(pubKeyHex)")
            }
          } catch {
            logger.error("Failed to fetch new contact: \(error.localizedDescription)")
            discoverTrace.error("B2 0x80 getContact THREW key=\(pubKeyHex): \(error.localizedDescription)")
          }
          eventBroadcaster.yield(.contactSyncRequested(radioID: radioID))
        }
      }
    } catch {
      logger.error("Error handling advert event: \(error.localizedDescription)")
    }
  }

  private func fetchPendingUnknownContacts() async {
    guard !pendingUnknownContactKeys.isEmpty else { return }
    guard let radioID = currentRadioID else {
      logger.warning("No device ID available to fetch pending contacts")
      return
    }

    let pendingKeys = pendingUnknownContactKeys
    pendingUnknownContactKeys.removeAll()

    for publicKey in pendingKeys {
      do {
        let pubKeyHex = publicKey.map { String(format: "%02X", $0) }.joined()
        if let meshContact = try await session.getContact(publicKey: publicKey) {
          let frame = meshContact.toContactFrame()
          let contactID = try await dataStore.saveContact(radioID: radioID, from: frame)

          // Also track in DiscoveredNode for Discover page visibility
          do {
            let (_, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
            discoverTrace.info("B2 deferred-drain upsert key=\(pubKeyHex) isNew=\(isNew)")
          } catch {
            discoverTrace.error("B2 deferred-drain upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
          }

          // Empty names pass through raw; NotificationService substitutes a localized fallback.
          let contactName = meshContact.advertisedName
          let contactType = meshContact.type
          eventBroadcaster.yield(.newContactDiscovered(name: contactName, contactID: contactID, contactType: contactType))
          eventBroadcaster.yield(.contactSyncRequested(radioID: radioID))
        }
      } catch {
        pendingUnknownContactKeys.insert(publicKey)
        logger.error("Failed to fetch deferred contact: \(error.localizedDescription)")
      }
    }
  }

  /// Handle new advertisement event - New contact discovered (manual add mode)
  private func handleNewAdvertEvent(contact: MeshContact, radioID: UUID) async {
    let contactFrame = contact.toContactFrame()
    let pubKeyHex = contactFrame.publicKey.map { String(format: "%02X", $0) }.joined()
    discoverTrace.info("B1 0x8A NEW_ADVERT received key=\(pubKeyHex)")

    do {
      let (node, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: contactFrame)
      discoverTrace.info("B2 0x8A upsert key=\(pubKeyHex) isNew=\(isNew)")

      // Notify UI of discovered node update
      eventBroadcaster.yield(.contactUpdated)

      // Only post notification for NEW discoveries (not repeat adverts from same contact)
      if isNew {
        let contactName = node.name
        let contactType = node.nodeType
        eventBroadcaster.yield(.newContactDiscovered(name: contactName, contactID: node.id, contactType: contactType))

        // Correlate with recent overwrite-oldest deletion
        logOverwriteReplacementIfRecent(newContactName: contactName, newContactType: contactType)
      }
    } catch {
      logger.error("Error handling new advert event: \(error.localizedDescription)")
      discoverTrace.error("B2 0x8A upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
    }
  }

  /// Handle path updated event - Contact path changed
  private func handlePathUpdatedEvent(publicKey: Data, radioID: UUID) async {
    let pubKeyHex = publicKey.map { String(format: "%02X", $0) }.joined()
    logger.debug("Path updated event for \(pubKeyHex)")

    do {
      // Fetch fresh contact from device (includes updated path)
      guard let meshContact = try await session.getContact(publicKey: publicKey) else {
        logger.warning("Contact not found on device for public key \(pubKeyHex)")
        return
      }

      // Persist updated routing info
      let frame = meshContact.toContactFrame()
      _ = try await dataStore.saveContact(radioID: radioID, from: frame)

      logger.debug("Refreshed contact path: \(meshContact.advertisedName.isEmpty ? "unnamed" : meshContact.advertisedName)")

      // Notify UI of contact update
      eventBroadcaster.yield(.contactUpdated)

    } catch {
      logger.error("Error refreshing contact path: \(error.localizedDescription)")
    }
  }

  /// Handle path discovery response event
  private func handlePathDiscoveryResponse(result: PathInfo, radioID: UUID) async {
    // Chunk debug output using the hash size each direction declares on
    // the wire so mode-skew between firmware and the cached device record
    // can't smear hop boundaries in the log.
    let outHashSize = decodePathLen(result.outPathLength)?.hashSize ?? 1
    let inHashSize = decodePathLen(result.inPathLength)?.hashSize ?? 1
    let outHops = stride(from: 0, to: result.outPath.count, by: outHashSize).map { start in
      result.outPath[start..<min(start + outHashSize, result.outPath.count)].map { String(format: "%02X", $0) }.joined()
    }
    let inHops = stride(from: 0, to: result.inPath.count, by: inHashSize).map { start in
      result.inPath[start..<min(start + inHashSize, result.inPath.count)].map { String(format: "%02X", $0) }.joined()
    }
    let pubKeyHex = result.publicKeyPrefix.prefix(3).map { String(format: "%02X", $0) }.joined()
    let outDisplay = outHops.isEmpty ? "direct" : outHops.joined(separator: " → ")
    let inDisplay = inHops.isEmpty ? "direct" : inHops.joined(separator: " → ")
    logger.info("Path discovery for \(pubKeyHex)... - Out: \(outHops.count) hops (\(outDisplay)), In: \(inHops.count) hops (\(inDisplay))")

    do {
      // Update contact with discovered outbound path (inbound is handled by firmware)
      if let contact = try await dataStore.fetchContact(radioID: radioID, publicKeyPrefix: result.publicKeyPrefix) {
        // Trust the wire's self-describing length byte over the device's
        // cached hashSize — the response's own encoding is authoritative.
        let frame = ContactFrame(
          publicKey: contact.publicKey,
          type: contact.type,
          flags: contact.flags,
          outPathLength: result.outPathLength,
          outPath: result.outPath,
          name: contact.name,
          lastAdvertTimestamp: contact.lastAdvertTimestamp,
          latitude: contact.latitude,
          longitude: contact.longitude,
          lastModified: UInt32(Date().timeIntervalSince1970)
        )
        _ = try await dataStore.saveContact(radioID: radioID, from: frame)
      }

      eventBroadcaster.yield(.pathDiscoveryResponse(result))
    } catch {
      logger.error("Error handling path discovery response: \(error.localizedDescription)")
    }
  }

  /// Handle trace data response
  private func handleTraceData(traceInfo: TraceInfo, radioID: UUID) async {
    logger.info("Received trace data: tag=\(traceInfo.tag), hops=\(traceInfo.path.count)")
    eventBroadcaster.yield(.traceResponse(traceInfo: traceInfo, radioID: radioID))
  }

  /// Handle contact deleted event (0x8F) - device auto-deleted a contact via overwrite oldest
  private func handleContactDeletedEvent(publicKey: Data, radioID: UUID) async {
    let fullPubKeyHex = publicKey.map { String(format: "%02X", $0) }.joined()
    let pubKeyPrefix = publicKey.prefix(6).map { String(format: "%02X", $0) }.joined()

    // ZephCore CLI `set v.contact off` also pushes 0x8F for the V-key. That is not
    // overwrite-oldest: no real slot was freed. Keep the local V row/messages and leave
    // storage-full bookkeeping unchanged.
    if let selfPublicKey = try? await dataStore.fetchDevice(radioID: radioID)?.publicKey,
       VContactIdentity.isVContact(publicKey: publicKey, selfPublicKey: selfPublicKey) {
      logger.info(
        "Contact deleted push for ZephCore V-contact (\(pubKeyPrefix)...); preserving local row, not clearing storage-full"
      )
      return
    }

    logger.info("Overwrite oldest: device deleted contact with key \(pubKeyPrefix)...")

    do {
      // Fetch contact by publicKey to get its UUID and details before deleting
      guard let contact = try await dataStore.fetchContact(radioID: radioID, publicKey: publicKey) else {
        logger.warning("Overwrite oldest: contact not found in local database for key \(pubKeyPrefix)... (may have been deleted already)")
        return
      }

      let contactName = contact.name.isEmpty ? "(unnamed)" : contact.name
      let contactTypeDesc = ContactType(rawValue: contact.typeRawValue).map { "\($0)" } ?? "unknown(\(contact.typeRawValue))"
      let lastModifiedDate = Date(timeIntervalSince1970: TimeInterval(contact.lastModified))
      let lastAdvertDate = Date(timeIntervalSince1970: TimeInterval(contact.lastAdvertTimestamp))

      logger.notice(
        """
        Overwrite oldest: deleting contact '\(contactName)' \
        [key=\(fullPubKeyHex), type=\(contactTypeDesc), favorite=\(contact.isFavorite), \
        pathLen=\(contact.outPathLength), lastModified=\(lastModifiedDate), lastAdvert=\(lastAdvertDate)]
        """
      )

      // Store deletion info for correlation with the replacement contact
      lastOverwriteDeletion = (name: contactName, pubKeyHex: pubKeyPrefix, time: Date())

      let contactID = contact.id

      try await dataStore.deleteContact(id: contactID)
      logger.info("Overwrite oldest: deleted contact '\(contactName)' and its messages from local database")

      // Trigger cleanup (notifications, badge, session)
      eventBroadcaster.yield(.contactDeletedCleanup(contactID: contactID, publicKey: publicKey))

      // Storage now has room - clear the full flag
      eventBroadcaster.yield(.nodeStorageFullChanged(isFull: false))
      logger.info("Overwrite oldest: cleanup complete for '\(contactName)', storage full flag cleared")

      // Notify UI to refresh contacts list
      eventBroadcaster.yield(.contactUpdated)
    } catch {
      logger.error("Overwrite oldest: failed to delete contact \(pubKeyPrefix)...: \(error.localizedDescription)")
    }
  }

  /// Log a correlation between an overwrite-oldest deletion and the new contact that replaced it.
  private func logOverwriteReplacementIfRecent(newContactName: String, newContactType: ContactType) {
    guard let deletion = lastOverwriteDeletion,
          Date().timeIntervalSince(deletion.time) < 60 else { return }

    logger.notice("Overwrite oldest: '\(deletion.name)' (\(deletion.pubKeyHex)...) replaced by '\(newContactName)' (type=\(newContactType))")
    lastOverwriteDeletion = nil
  }

  /// Handle contacts full event (0x90) - device storage is full
  private func handleContactsFullEvent() async {
    logger.warning("Device node storage is full - if overwrite oldest is enabled, the next new node will trigger auto-deletion of the oldest non-favorite contact")
    eventBroadcaster.yield(.nodeStorageFullChanged(isFull: true))
  }
}
