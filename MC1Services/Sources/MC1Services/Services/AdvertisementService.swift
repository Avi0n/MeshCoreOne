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

// MARK: - Advert Contact Sync Outcome

/// Result of one advert-driven contact delta exchange.
///
/// `busy` is not `failed`: a collision with another sync never reaches the
/// radio, so it must not spend the failure budget that drops drained keys.
/// `notReady` is permanent until a full contact fetch succeeds: neither requeue
/// nor spend the failure budget.
public enum AdvertContactSyncOutcome: Sendable {
  case synced
  case busy
  case failed
  case notReady
}

// MARK: - Advertisement Service

/// Service for managing device advertisements and discovery.
/// Handles sending self-advertisements and processing incoming adverts via MeshCore events.
public actor AdvertisementService {
  // MARK: - Properties

  let logger = PersistentLogger(subsystem: "com.mc1", category: "Advertisement")

  /// End-to-end Discover trace (Logger category `discover-trace`).
  let discoverTrace = PersistentLogger(subsystem: "com.mc1", category: "discover-trace")

  let session: any AdvertisingSessionOps & SessionEventStreaming
  let dataStore: any PersistenceStoreProtocol

  private var eventMonitorTask: Task<Void, Never>?
  /// In-flight `flushPendingDeletedKeys`. Callers wait through
  /// `.contactDeletedCleanup`, not only `deleteContacts`.
  private var deletionFlushTask: Task<Void, Never>?
  var currentRadioID: UUID?

  /// When true, advert delta sync is deferred (full or manual contact sync).
  /// Manual pull-to-refresh also sets this; it is invisible to `SyncCoordinator.isSyncInProgress`.
  var isSyncingContacts = false

  /// 0x80 keys heard since the last delta sync.
  var pendingAdvertKeys: Set<Data> = []

  /// Phone receive times for pending 0x80 keys, keyed by public key.
  /// Stamped onto `lastHeardTimestamp` after a delta insert (or materialize)
  /// so a stale radio RTC cannot prune a contact just heard on air.
  private var pendingAdvertReceiveTimes: [Data: Date] = [:]

  /// 0x81 path-update keys waiting for the next delta sync. Tracked apart from
  /// `pendingAdvertKeys` because a path key must not create a Discover row.
  /// Keys (not a bool) so a successful incremental round can verify delivery
  /// when radio `lastmod` falls at or below the stored watermark.
  var pendingPathKeys: Set<Data> = []

  /// True when any 0x81 path key is waiting for delta sync.
  var pathSyncPending: Bool {
    !pendingPathKeys.isEmpty
  }

  /// Keys the radio deleted (0x8F) since this round drained its keys. Rolled back so
  /// a batch cannot re-save a dropped row, and skipped while reconciling. Cleared
  /// when the next round drains, so a between-round delete never undoes a later
  /// re-created contact.
  var contactsDeletedDuringSync: Set<Data> = []

  /// Drain-surviving 0x8F queue. Not `contactsDeletedDuringSync` (that is still
  /// round-scoped and cleared at drain). Flushed on foreground or `stopEventMonitoring`.
  var pendingDeletedKeys: Set<Data> = []

  /// Keys revived while `flushPendingDeletedKeys` is off-actor in `deleteContacts`.
  /// Read per key so a same-key 0x80 during that hop cannot cascade-delete the row.
  private let revivedDuringDeleteFlush = OSAllocatedUnfairLock(initialState: Set<Data>())

  var deltaSyncTask: Task<Void, Never>?
  /// Monotonic identity for the scheduled delta-sync round. `finishRound` clears
  /// `deltaSyncTask` only while this still matches the running round.
  var deltaSyncGeneration: UInt64 = 0
  var lastDeltaSyncEnd: ContinuousClock.Instant?
  var deltaSyncHandler: (@Sendable (_ fullRefetch: Bool) async -> AdvertContactSyncOutcome)?
  /// One-shot: the next delta sync runs as a prune-free full fetch.
  var escalateToFullRefetch = false

  /// Delta syncs that failed back to back without an intervening success.
  var consecutiveDeltaSyncFailures = 0

  /// Failed delta syncs tolerated before a round drops its drained keys. Without a
  /// cap, a handler that never succeeds repeats a full contact exchange forever.
  static let maxConsecutiveDeltaSyncFailures = 5

  let advertSyncDebounce: Duration
  let advertSyncMinInterval: Duration
  /// Backoff before re-arming after a `.busy` outcome. Shorter than
  /// `advertSyncMinInterval` so claim collisions poll cheaply without spinning.
  let advertSyncBusyBackoff: Duration
  let appStateProvider: AppStateProvider?

  /// Last overwrite-oldest deletion, used to correlate the replacement advert (0x8F then new contact).
  private var lastOverwriteDeletion: (name: String, pubKeyHex: String, time: Date)?

  /// Multicast broadcaster for advertisement and discovery events.
  /// Producers yield synchronously; consumers subscribe via `events()`.
  nonisolated let eventBroadcaster = EventBroadcaster<AdvertisementEvent>()

  // MARK: - Initialization

  public init(
    session: any AdvertisingSessionOps & SessionEventStreaming,
    dataStore: any PersistenceStoreProtocol,
    advertSyncDebounce: Duration = .seconds(5),
    advertSyncMinInterval: Duration = .seconds(30),
    advertSyncBusyBackoff: Duration = .seconds(5),
    appStateProvider: AppStateProvider? = nil
  ) {
    self.session = session
    self.dataStore = dataStore
    self.advertSyncDebounce = advertSyncDebounce
    self.advertSyncMinInterval = advertSyncMinInterval
    self.advertSyncBusyBackoff = advertSyncBusyBackoff
    self.appStateProvider = appStateProvider
  }

  func isInForeground() async -> Bool {
    guard let appStateProvider else { return true }
    return await appStateProvider.isInForeground
  }

  func isRadioDeleted(_ key: Data) -> Bool {
    contactsDeletedDuringSync.contains(key) || pendingDeletedKeys.contains(key)
  }

  /// Cancels outstanding tasks only. Full teardown is ``stopEventMonitoring``
  /// (`ServiceContainer.tearDown`); deinit cannot safely clear the handler.
  deinit {
    eventMonitorTask?.cancel()
    deltaSyncTask?.cancel()
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
        await handleEvent(event, radioID: radioID)
        if Task.isCancelled { break }
      }
    }
  }

  /// Stops event monitoring and clears advert delta-sync state.
  public func stopEventMonitoring() async {
    let monitor = eventMonitorTask
    eventMonitorTask = nil
    monitor?.cancel()
    // Drop the handler and bump deltaSyncGeneration before any await so the
    // in-flight round cannot reconcile or re-arm.
    deltaSyncHandler = nil
    deltaSyncTask?.cancel()
    deltaSyncGeneration &+= 1
    // Await `flushPendingDeletedKeys` before clearing `currentRadioID` so a queued
    // 0x8F cannot outlive `stopEventMonitoring`.
    await monitor?.value
    await flushPendingDeletedKeys()
    let receiveTimes = pendingAdvertReceiveTimes
    await stampAdvertReceiveTimes(receiveTimes, radioID: currentRadioID)
    currentRadioID = nil
    deltaSyncTask = nil
    pendingAdvertKeys.removeAll()
    pendingPathKeys.removeAll()
    pendingAdvertReceiveTimes.removeAll()
    escalateToFullRefetch = false
    consecutiveDeltaSyncFailures = 0
    isSyncingContacts = false
    // Keep `contactsDeletedDuringSync` across teardown so a commit still in flight
    // can roll back rows the radio deleted; the next round clears it when it drains.
  }

  /// Flushes queued 0x8F deletes, then re-arms when `hasPendingDeltaSyncWork`.
  /// Does not re-read `isInForeground`; `AppState.handleReturnToForeground` already ran.
  public func handleReturnToForeground() async {
    await flushPendingDeletedKeys()
    if hasPendingDeltaSyncWork {
      scheduleDeltaSync()
    }
  }

  func flushPendingDeletedKeys() async {
    if let deletionFlushTask {
      await deletionFlushTask.value
      return
    }
    guard !pendingDeletedKeys.isEmpty, let radioID = currentRadioID else { return }

    // Await this Task through `.contactDeletedCleanup` and the catch requeue, which
    // run after `deleteContacts`. A store error ends this flush.
    let task = Task {
      defer {
        revivedDuringDeleteFlush.withLock { $0.removeAll() }
        deletionFlushTask = nil
      }
      while !pendingDeletedKeys.isEmpty {
        let keys = pendingDeletedKeys
        pendingDeletedKeys.removeAll()
        do {
          let deletedIDs = try await dataStore.deleteContacts(
            radioID: radioID,
            publicKeys: keys,
            skippingPublicKeys: { revivedDuringDeleteFlush.withLock { $0 } }
          )
          guard !deletedIDs.isEmpty else { continue }
          logger.notice("Overwrite oldest: applying \(deletedIDs.count) deferred deletion(s)")
          eventBroadcaster.yield(.contactDeletedCleanup(contactIDs: deletedIDs))
          eventBroadcaster.yield(.nodeStorageFullChanged(isFull: false))
          eventBroadcaster.yield(.contactUpdated)
        } catch {
          pendingDeletedKeys.formUnion(keys)
          pendingDeletedKeys.subtract(pendingAdvertKeys)
          logger.error(
            "Overwrite oldest: deferred delete flush failed: \(error.localizedDescription)"
          )
          return
        }
      }
    }
    deletionFlushTask = task
    await task.value
  }

  /// Records a 0x80 key for the next delta sync.
  ///
  /// A re-advert for a key in `contactsDeletedDuringSync` or `pendingDeletedKeys`
  /// means the radio re-added it after 0x8F. Clear both so later paths treat it as live.
  ///
  /// `receivedAt` is the phone clock at the 0x80 (or re-record). It is applied
  /// to `lastHeardTimestamp` once a Contact row exists so prune recency is not
  /// hostage to a stale radio RTC.
  func recordPendingAdvertKey(_ key: Data, receivedAt: Date = Date()) {
    pendingAdvertKeys.insert(key)
    contactsDeletedDuringSync.remove(key)
    pendingDeletedKeys.remove(key)
    revivedDuringDeleteFlush.withLock { $0.formUnion([key]) }
    pendingAdvertReceiveTimes[key] = receivedAt
  }

  /// Captures and removes phone receive times for keys about to drain.
  func takeAdvertReceiveTimes(for keys: Set<Data>) -> [Data: Date] {
    var times: [Data: Date] = [:]
    times.reserveCapacity(keys.count)
    for key in keys {
      if let receivedAt = pendingAdvertReceiveTimes.removeValue(forKey: key) {
        times[key] = receivedAt
      }
    }
    return times
  }

  /// Stamps `lastHeardTimestamp` for drained 0x80 keys that now have a Contact
  /// row, using each key's recorded phone receive time. Uses
  /// `touchContactHeard` so clamping matches the live 0x80 path.
  func stampAdvertReceiveTimes(_ receiveTimes: [Data: Date], radioID: UUID?) async {
    guard let radioID, !receiveTimes.isEmpty else { return }
    for (publicKey, receivedAt) in receiveTimes {
      guard !isRadioDeleted(publicKey) else { continue }
      do {
        _ = try await dataStore.touchContactHeard(
          radioID: radioID, publicKey: publicKey, at: receivedAt
        )
      } catch {
        let pubKeyHex = publicKey.uppercaseHexString()
        logger.error(
          "Post-delta lastHeard stamp failed for \(pubKeyHex): \(error.localizedDescription)"
        )
      }
    }
  }

  /// True when a delta sync still has work: pending 0x80 keys, a path update,
  /// or an owed prune-free full refetch. Re-arm sites and the empty-round
  /// guard share this definition so a future flag cannot desynchronise them.
  var hasPendingDeltaSyncWork: Bool {
    !pendingAdvertKeys.isEmpty || pathSyncPending || escalateToFullRefetch
  }

  /// Creates a local `Contact` from a pending 0x80 key so a DM that arrives
  /// inside the advert delta-sync debounce window can notify and list normally.
  ///
  /// Matches `prefix` against `pendingAdvertKeys` only (keys the radio already
  /// auto-added). Exactly one match is required; ambiguous prefixes return nil.
  /// The key stays pending so the debounced delta round still reconciles Discover
  /// and may emit `.newContactDiscovered`.
  ///
  /// - Returns: The persisted contact, or nil when no unique pending key matches
  ///   or the radio fetch / save fails.
  public func materializeContactForPendingAdvert(
    matchingPrefix prefix: Data,
    radioID: UUID
  ) async -> ContactDTO? {
    guard !prefix.isEmpty else { return nil }

    let matches = pendingAdvertKeys.filter { $0.starts(with: prefix) }
    guard matches.count == 1, let publicKey = matches.first else { return nil }

    let pubKeyHex = publicKey.uppercaseHexString()
    do {
      guard let meshContact = try await session.getContact(publicKey: publicKey) else {
        logger.info("materialize pending advert: getContact nil key=\(pubKeyHex)")
        return nil
      }
      let frame = meshContact.toContactFrame()
      let saveResult = try await dataStore.saveContact(radioID: radioID, from: frame)
      // Stamp phone recency before the returned DTO is read so a debounce-window
      // insert is not prune-eligible under a stale radio lastModified.
      let receivedAt = pendingAdvertReceiveTimes[publicKey] ?? Date()
      do {
        _ = try await dataStore.touchContactHeard(
          radioID: radioID, publicKey: publicKey, at: receivedAt
        )
      } catch {
        logger.error(
          "materialize pending advert lastHeard stamp failed key=\(pubKeyHex): \(error.localizedDescription)"
        )
      }
      do {
        _ = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
      } catch {
        logger.error(
          "materialize pending advert Discover upsert failed key=\(pubKeyHex): \(error.localizedDescription)"
        )
      }
      eventBroadcaster.yield(.contactUpdated)
      return try await dataStore.fetchContact(id: saveResult.id)
    } catch {
      logger.error(
        "materialize pending advert failed key=\(pubKeyHex): \(error.localizedDescription)"
      )
      return nil
    }
  }

  /// Installs the contact delta-sync handler used after debounced 0x80/0x81 events.
  /// A non-nil handler re-arms when work is already pending (adverts, path updates,
  /// or an owed full refetch that arrived before wiring).
  public func setDeltaSyncHandler(
    _ handler: (@Sendable (_ fullRefetch: Bool) async -> AdvertContactSyncOutcome)?
  ) {
    deltaSyncHandler = handler
    if handler != nil, hasPendingDeltaSyncWork {
      scheduleDeltaSync()
    }
  }

  /// Toggle deferred contact sync during a full or manual contact sync.
  /// When set to `false` with pending work, re-arms so a mid-sync debounce does
  /// not drop keys, path updates, or an owed full refetch.
  public func setSyncingContacts(_ isSyncing: Bool) async {
    isSyncingContacts = isSyncing
    if !isSyncing, hasPendingDeltaSyncWork, await isInForeground() {
      scheduleDeltaSync()
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

  /// 0x80 change notification: the radio already updated a contact row.
  private func handleAdvertEvent(publicKey: Data, radioID: UUID) async {
    let pubKeyHex = publicKey.uppercaseHexString()
    logger.debug("Advert event for \(pubKeyHex)")
    discoverTrace.debug("B1 0x80 ADVERT received key=\(pubKeyHex)")

    // One phone clock for touch and pending receive-time so the post-delta
    // stamp matches the air-hear moment (not a later debounce fire).
    let receivedAt = Date()
    // Visible to an in-flight `deleteContacts` on PersistenceStore. Do not
    // `pendingDeletedKeys.remove` here: a failed touch must not drop a queued 0x8F.
    revivedDuringDeleteFlush.withLock { $0.formUnion([publicKey]) }
    let inForeground = await isInForeground()

    if !inForeground {
      recordPendingAdvertKey(publicKey, receivedAt: receivedAt)
      return
    }

    // Retry touch once: a transient store error must not permanently drop the
    // advert under the empty-round guard. Recording without a successful touch
    // is also wrong (it can announce a long-known contact as new).
    var known: Bool?
    do {
      known = try await dataStore.touchContactHeard(
        radioID: radioID, publicKey: publicKey, at: receivedAt
      )
    } catch {
      logger.error("Error handling advert event (retrying once): \(error.localizedDescription)")
      do {
        known = try await dataStore.touchContactHeard(
          radioID: radioID, publicKey: publicKey, at: receivedAt
        )
      } catch {
        logger.error("Advert touch retry failed: \(error.localizedDescription)")
        known = nil
      }
    }

    if let known {
      if known {
        eventBroadcaster.yield(.contactUpdated)
      } else {
        discoverTrace.debug("B2 0x80 no local contact key=\(pubKeyHex) syncing=\(isSyncingContacts)")
        logger.debug("ADVERT received for unknown contact - scheduling delta sync")
      }
      recordPendingAdvertKey(publicKey, receivedAt: receivedAt)
    }

    // Schedule so other pending keys or a path update still run; the empty-round
    // guard no-ops when this advert alone failed both touch attempts.
    scheduleDeltaSync()
  }

  /// Handle new advertisement event - New contact discovered (manual add mode)
  private func handleNewAdvertEvent(contact: MeshContact, radioID: UUID) async {
    let contactFrame = contact.toContactFrame()
    let pubKeyHex = contactFrame.publicKey.uppercaseHexString()
    discoverTrace.debug("B1 0x8A NEW_ADVERT received key=\(pubKeyHex)")

    do {
      let (node, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: contactFrame)
      discoverTrace.debug("B2 0x8A upsert key=\(pubKeyHex) isNew=\(isNew)")

      eventBroadcaster.yield(.contactUpdated)

      // Notify only first-time discoveries, not repeat adverts for the same contact.
      if isNew {
        let contactName = node.name
        let contactType = node.nodeType
        eventBroadcaster.yield(.newContactDiscovered(name: contactName, contactID: node.id, contactType: contactType))
        logOverwriteReplacementIfRecent(newContactName: contactName, newContactType: contactType)
      }
    } catch {
      logger.error("Error handling new advert event: \(error.localizedDescription)")
      discoverTrace.error("B2 0x8A upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
    }
  }

  /// Path changed on the radio: record the key and schedule contact delta sync.
  /// Path keys must not create Discover rows; path changes are not on-air heard evidence.
  /// Delivery is via watermark-filtered GET_CONTACTS; if radio `lastmod` is at or
  /// below the stored watermark the incremental round returns nothing and the
  /// post-round path check escalates once to a prune-free full refetch.
  private func handlePathUpdatedEvent(publicKey: Data, radioID: UUID) async {
    let pubKeyHex = publicKey.uppercaseHexString()
    logger.debug("Path updated event for \(pubKeyHex)")
    pendingPathKeys.insert(publicKey)
    if await isInForeground() {
      scheduleDeltaSync()
    }
  }

  /// Path discovery response: update out-path and stamp phone-clock lastHeard.
  /// Leaves radio lastModified unchanged — it is a radio watermark, not phone time.
  private func handlePathDiscoveryResponse(result: PathInfo, radioID: UUID) async {
    // Chunk debug output using the hash size each direction declares on
    // the wire so mode-skew between firmware and the cached device record
    // can't smear hop boundaries in the log.
    let outHashSize = decodePathLen(result.outPathLength)?.hashSize ?? 1
    let inHashSize = decodePathLen(result.inPathLength)?.hashSize ?? 1
    let outHops = stride(from: 0, to: result.outPath.count, by: outHashSize).map { start in
      result.outPath[start..<min(start + outHashSize, result.outPath.count)].uppercaseHexString()
    }
    let inHops = stride(from: 0, to: result.inPath.count, by: inHashSize).map { start in
      result.inPath[start..<min(start + inHashSize, result.inPath.count)].uppercaseHexString()
    }
    let pubKeyHex = result.publicKeyPrefix.prefix(3).uppercaseHexString()
    let outDisplay = outHops.isEmpty ? "direct" : outHops.joined(separator: " → ")
    let inDisplay = inHops.isEmpty ? "direct" : inHops.joined(separator: " → ")
    logger.info("Path discovery for \(pubKeyHex)... - Out: \(outHops.count) hops (\(outDisplay)), In: \(inHops.count) hops (\(inDisplay))")

    do {
      if let contact = try await dataStore.fetchContact(radioID: radioID, publicKeyPrefix: result.publicKeyPrefix) {
        // Prefer the response's self-describing length byte over the device's
        // cached hashSize — the wire encoding is authoritative for this path.
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
          lastModified: contact.lastModified
        )
        _ = try await dataStore.saveContact(radioID: radioID, from: frame)

        do {
          _ = try await dataStore.touchContactHeard(
            radioID: radioID,
            publicKey: contact.publicKey,
            at: Date()
          )
        } catch {
          logger.error(
            "Path response lastHeard stamp failed: \(error.localizedDescription)"
          )
        }
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
    let fullPubKeyHex = publicKey.uppercaseHexString()
    let pubKeyPrefix = publicKey.prefix(6).uppercaseHexString()

    // ZephCore CLI `set v.contact off` also pushes 0x8F for the V-key. That is
    // not overwrite-oldest (no slot freed). Keep the local V row and leave
    // storage-full bookkeeping unchanged.
    if let selfPublicKey = try? await dataStore.fetchDevice(radioID: radioID)?.publicKey,
       VContactIdentity.isVContact(publicKey: publicKey, selfPublicKey: selfPublicKey) {
      logger.info(
        "Contact deleted push for ZephCore V-contact (\(pubKeyPrefix)...); preserving local row, not clearing storage-full"
      )
      return
    }

    // Drop pending reconcile / path keys so a deleted contact is not re-notified
    // and a surviving path key cannot escalate to an epoch-0 full refetch.
    pendingAdvertKeys.remove(publicKey)
    pendingPathKeys.remove(publicKey)
    pendingAdvertReceiveTimes.removeValue(forKey: publicKey)
    // A running delta sync can re-save or reconcile this row after the radio dropped
    // it. A key recorded outside a round is discarded when the next round drains.
    contactsDeletedDuringSync.insert(publicKey)
    // Enqueue before `isInForeground` so a concurrent flush cannot miss this
    // key while the actor is suspended.
    revivedDuringDeleteFlush.withLock { _ = $0.remove(publicKey) }
    pendingDeletedKeys.insert(publicKey)

    if await !isInForeground() {
      return
    }

    guard pendingDeletedKeys.remove(publicKey) != nil else { return }

    logger.debug("Overwrite oldest: device deleted contact with key \(pubKeyPrefix)...")

    do {
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

      lastOverwriteDeletion = (name: contactName, pubKeyHex: pubKeyPrefix, time: Date())

      let contactID = contact.id

      try await dataStore.deleteContact(id: contactID)
      logger.debug("Overwrite oldest: deleted contact '\(contactName)' and its messages from local database")

      eventBroadcaster.yield(.contactDeletedCleanup(contactIDs: [contactID]))
      eventBroadcaster.yield(.nodeStorageFullChanged(isFull: false))
      logger.debug("Overwrite oldest: cleanup complete for '\(contactName)', storage full flag cleared")
      eventBroadcaster.yield(.contactUpdated)
    } catch {
      pendingDeletedKeys.insert(publicKey)
      logger.error(
        "Overwrite oldest: failed to delete contact \(pubKeyPrefix)...: \(error.localizedDescription)"
      )
    }
  }

  /// Correlates an overwrite-oldest deletion with a replacement contact seen soon after.
  func logOverwriteReplacementIfRecent(newContactName: String, newContactType: ContactType) {
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
