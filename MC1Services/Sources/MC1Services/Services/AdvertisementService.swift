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
        case .sessionError(let e): e.localizedDescription
        }
    }
}

// MARK: - Advertisement Service

/// Service for managing device advertisements and discovery.
/// Handles sending self-advertisements and processing incoming adverts via MeshCore events.
public actor AdvertisementService {

    // MARK: - Properties

    private let logger = PersistentLogger(subsystem: "com.mc1", category: "Advertisement")

    private let session: MeshCoreSession
    private let dataStore: PersistenceStore

    /// Task monitoring for events
    private var eventMonitorTask: Task<Void, Never>?
    private var currentRadioID: UUID?

    /// Whether contact fetches should be deferred (during sync)
    private var isSyncingContacts = false
    private var pendingUnknownContactKeys: Set<Data> = []

    /// Tracks the last overwrite-oldest deletion for correlating with the replacement contact.
    /// The device sends 0x8F (deleted) then shortly after an advert for the new contact.
    private var lastOverwriteDeletion: (name: String, pubKeyHex: String, time: Date)?

    /// Handler for new advertisement events (for UI updates)
    private var advertHandler: (@Sendable (ContactFrame) -> Void)?

    /// Handler for path update events
    private var pathUpdateHandler: (@Sendable (Data, Int8) -> Void)?

    /// Handler for path discovery response events
    private var pathDiscoveryHandler: (@Sendable (PathInfo) -> Void)?

    /// Handler for routing change events (set by AppState)
    private var routingChangedHandler: (@Sendable (UUID, Bool) async -> Void)?

    /// Handler for contact update events (for UI refresh)
    private var contactUpdatedHandler: (@Sendable () async -> Void)?

    // MARK: - Discovery Handlers

    /// Handler for new contact discovered events (for notifications)
    /// Parameters: contactName, contactID, contactType
    private var newContactDiscoveredHandler: (@Sendable (String, UUID, ContactType) async -> Void)?

    /// Handler for contact sync request events (when ADVERT received for unknown contact)
    private var contactSyncRequestHandler: (@Sendable (UUID) async -> Void)?

    /// Handler for node storage full state changes (true = full, false = has space)
    private var nodeStorageFullChangedHandler: (@Sendable (Bool) async -> Void)?

    /// Handler for contact deleted cleanup (notifications, badge, session)
    /// Parameters: contactID, publicKey
    private var contactDeletedCleanupHandler: (@Sendable (UUID, Data) async -> Void)?

    /// Cache local reception SNR from rxLogData for trace responses (tag → SNR)
    private var traceLocalSnr: [UInt32: Double] = [:]

    // MARK: - Initialization

    public init(session: MeshCoreSession, dataStore: PersistenceStore) {
        self.session = session
        self.dataStore = dataStore
    }

    deinit {
        eventMonitorTask?.cancel()
    }

    // MARK: - Event Handlers

    /// Set handler for new advertisement events
    public func setAdvertHandler(_ handler: @escaping @Sendable (ContactFrame) -> Void) {
        advertHandler = handler
    }

    /// Set handler for path update events
    public func setPathUpdateHandler(_ handler: @escaping @Sendable (Data, Int8) -> Void) {
        pathUpdateHandler = handler
    }

    /// Set handler for path discovery response events
    public func setPathDiscoveryHandler(_ handler: @escaping @Sendable (PathInfo) -> Void) {
        pathDiscoveryHandler = handler
    }

    /// Set handler for routing change events
    public func setRoutingChangedHandler(_ handler: @escaping @Sendable (UUID, Bool) async -> Void) {
        routingChangedHandler = handler
    }

    /// Set handler for contact update events (called when contacts change)
    public func setContactUpdatedHandler(_ handler: @escaping @Sendable () async -> Void) {
        contactUpdatedHandler = handler
    }

    /// Set handler for new contact discovered events (for posting notifications)
    public func setNewContactDiscoveredHandler(_ handler: @escaping @Sendable (String, UUID, ContactType) async -> Void) {
        newContactDiscoveredHandler = handler
    }

    /// Set handler for contact sync requests (called when ADVERT received for unknown contact)
    public func setContactSyncRequestHandler(_ handler: @escaping @Sendable (UUID) async -> Void) {
        contactSyncRequestHandler = handler
    }

    /// Clears the discovery handlers wired by `SyncCoordinator`. Both capture
    /// the owning `ServiceContainer` strongly, so leaving them in place keeps the
    /// whole service graph alive after the container is torn down on disconnect.
    public func clearDiscoveryHandlers() {
        newContactDiscoveredHandler = nil
        contactSyncRequestHandler = nil
    }

    /// Whether any discovery handler is currently wired.
    var hasDiscoveryHandlersWired: Bool {
        newContactDiscoveredHandler != nil || contactSyncRequestHandler != nil
    }

    /// Set handler for node storage full state changes (called when 0x90 or 0x8F push received)
    public func setNodeStorageFullChangedHandler(_ handler: @escaping @Sendable (Bool) async -> Void) {
        nodeStorageFullChangedHandler = handler
    }

    /// Set handler for contact deleted cleanup (called when device auto-deletes via 0x8F)
    public func setContactDeletedCleanupHandler(_ handler: @escaping @Sendable (UUID, Data) async -> Void) {
        contactDeletedCleanupHandler = handler
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
                    return true
                case .rxLogData(let log) where log.payloadType == .trace:
                    return true
                default:
                    return false
                }
            }
            let events = await session.events(filter: filter)

            for await event in events {
                guard !Task.isCancelled else { break }
                await self.handleEvent(event, radioID: radioID)
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
        case .advertisement(let publicKey):
            await handleAdvertEvent(publicKey: publicKey, radioID: radioID)

        case .newContact(let contact):
            await handleNewAdvertEvent(contact: contact, radioID: radioID)

        case .pathUpdate(let publicKey):
            await handlePathUpdatedEvent(publicKey: publicKey, radioID: radioID)

        case .pathResponse(let result):
            await handlePathDiscoveryResponse(result: result, radioID: radioID)

        case .traceData(let traceInfo):
            await handleTraceData(traceInfo: traceInfo, radioID: radioID)

        case .rxLogData(let logData) where logData.payloadType == .trace:
            if logData.packetPayload.count >= 4, let snr = logData.snr {
                let tag = logData.packetPayload.readUInt32LE(at: 0)
                traceLocalSnr[tag] = snr
                let remoteSnr: Double? = logData.pathNodes.last.map {
                    Double(Int8(bitPattern: $0)) / 4.0
                }
                await MainActor.run {
                    var userInfo: [String: Any] = ["tag": tag, "localSnr": snr, "radioID": radioID]
                    if let remoteSnr {
                        userInfo["remoteSnr"] = remoteSnr
                    }
                    NotificationCenter.default.post(
                        name: .rxLogTraceReceived,
                        object: nil,
                        userInfo: userInfo
                    )
                }
            }

        case .contactDeleted(let publicKey):
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
                _ = try? await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)

                advertHandler?(frame)

                // Notify UI of contact update
                await contactUpdatedHandler?()
            } else {
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
                            _ = try? await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)

                            let contactName = meshContact.advertisedName.isEmpty ? "Unknown Contact" : meshContact.advertisedName
                            let contactType = meshContact.type
                            await newContactDiscoveredHandler?(contactName, contactID, contactType)

                            // Correlate with recent overwrite-oldest deletion
                            logOverwriteReplacementIfRecent(newContactName: contactName, newContactType: contactType)
                        }
                    } catch {
                        logger.error("Failed to fetch new contact: \(error.localizedDescription)")
                    }
                    await contactSyncRequestHandler?(radioID)
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
                if let meshContact = try await session.getContact(publicKey: publicKey) {
                    let frame = meshContact.toContactFrame()
                    let contactID = try await dataStore.saveContact(radioID: radioID, from: frame)

                    // Also track in DiscoveredNode for Discover page visibility
                    _ = try? await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)

                    let contactName = meshContact.advertisedName.isEmpty ? "Unknown Contact" : meshContact.advertisedName
                    let contactType = meshContact.type
                    await newContactDiscoveredHandler?(contactName, contactID, contactType)
                    await contactSyncRequestHandler?(radioID)
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

        do {
            let (node, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: contactFrame)
            advertHandler?(contactFrame)

            // Notify UI of discovered node update
            await contactUpdatedHandler?()

            // Only post notification for NEW discoveries (not repeat adverts from same contact)
            if isNew {
                let contactName = node.name
                let contactType = node.nodeType
                await newContactDiscoveredHandler?(contactName, node.id, contactType)

                // Correlate with recent overwrite-oldest deletion
                logOverwriteReplacementIfRecent(newContactName: contactName, newContactType: contactType)
            }
        } catch {
            logger.error("Error handling new advert event: \(error.localizedDescription)")
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
            await contactUpdatedHandler?()

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
                let wasFlood = contact.isFloodRouted  // Capture before database write

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

                // Path discovery success = we have a direct route now (not flood)
                let isNowFlood = false

                // Notify UI if routing status changed (flood → direct after path discovery)
                if wasFlood && !isNowFlood {
                    await routingChangedHandler?(contact.id, isNowFlood)
                }
            }

            pathDiscoveryHandler?(result)
        } catch {
            logger.error("Error handling path discovery response: \(error.localizedDescription)")
        }
    }

    /// Handle trace data response
    private func handleTraceData(traceInfo: TraceInfo, radioID: UUID) async {
        let localSnr = traceLocalSnr.removeValue(forKey: traceInfo.tag)
        logger.info("Received trace data: tag=\(traceInfo.tag), hops=\(traceInfo.path.count)")
        await MainActor.run {
            var userInfo: [String: Any] = ["traceInfo": traceInfo, "radioID": radioID]
            if let localSnr {
                userInfo["localSnr"] = localSnr
            }
            NotificationCenter.default.post(
                name: .traceDataReceived,
                object: nil,
                userInfo: userInfo
            )
        }
    }

    /// Handle contact deleted event (0x8F) - device auto-deleted a contact via overwrite oldest
    private func handleContactDeletedEvent(publicKey: Data, radioID: UUID) async {
        let fullPubKeyHex = publicKey.map { String(format: "%02X", $0) }.joined()
        let pubKeyPrefix = publicKey.prefix(6).map { String(format: "%02X", $0) }.joined()
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

            // Delete associated messages
            try await dataStore.deleteMessagesForContact(contactID: contactID)
            logger.info("Overwrite oldest: deleted messages for contact '\(contactName)'")

            // Delete the contact
            try await dataStore.deleteContact(id: contactID)
            logger.info("Overwrite oldest: deleted contact '\(contactName)' from local database")

            // Trigger cleanup (notifications, badge, session)
            await contactDeletedCleanupHandler?(contactID, publicKey)

            // Storage now has room - clear the full flag
            await nodeStorageFullChangedHandler?(false)
            logger.info("Overwrite oldest: cleanup complete for '\(contactName)', storage full flag cleared")

            // Notify UI to refresh contacts list
            await contactUpdatedHandler?()
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
        await nodeStorageFullChangedHandler?(true)
    }
}
