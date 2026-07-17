import Foundation
import MeshCore

// MARK: - Contact Fetch Queue

extension AdvertisementService {
  /// Why a contact fetch was requested; determines post-fetch persistence and events.
  enum ContactFetchReason {
    case advert
    case pathUpdate
  }

  struct ContactFetchEntry {
    var reason: ContactFetchReason
    /// Captured at enqueue so teardown (which nils currentRadioID) can't misattribute work.
    var radioID: UUID
  }

  func enqueueContactFetch(_ publicKey: Data, reason: ContactFetchReason, radioID: UUID) {
    if var existing = contactFetchQueue[publicKey] {
      // Advert post-fetch work is a superset of pathUpdate's.
      if reason == .advert {
        existing.reason = .advert
      }
      existing.radioID = radioID
      contactFetchQueue[publicKey] = existing
    } else {
      contactFetchQueue[publicKey] = ContactFetchEntry(reason: reason, radioID: radioID)
    }
    startContactFetchWorkerIfNeeded()
  }

  func startContactFetchWorkerIfNeeded() {
    guard contactFetchWorker == nil else { return }
    guard !isSyncingContacts else { return }
    guard !contactFetchQueue.isEmpty else { return }

    contactFetchWorkerGeneration &+= 1
    let generation = contactFetchWorkerGeneration
    contactFetchWorker = Task { [weak self] in
      await self?.runContactFetchWorker(generation: generation)
    }
  }

  func runContactFetchWorker(generation: UInt64) async {
    // Each iteration is one snapshotted pass. Barrier waiters resume at pass
    // boundaries so late joiners never await drain-until-empty under live traffic.
    while !Task.isCancelled, !isSyncingContacts {
      let passKeys = Set(contactFetchQueue.keys)
      // Emptiness check and generation-matched nil share one actor-isolated
      // region with no await between them (lost-wakeup handshake).
      if passKeys.isEmpty {
        break
      }

      for publicKey in passKeys {
        guard !Task.isCancelled else { break }
        guard !isSyncingContacts else { break }
        // 0x8F may have removed this entry after the pass was snapshotted.
        guard contactFetchQueue[publicKey] != nil else { continue }

        await processContactFetch(publicKey: publicKey)
      }

      // Always resume waiters at the pass boundary, including after throws.
      resumeBarrierWaiters()
    }

    // Only the active worker may clear the ref (generation match).
    if contactFetchWorkerGeneration == generation {
      contactFetchWorker = nil
    }
    resumeBarrierWaiters()
  }

  /// True when 0x8F/teardown cancelled the airborne fetch, or the worker Task was cancelled.
  var isCommitCancelled: Bool {
    Task.isCancelled || inFlightCancelled
  }

  /// Removes the queue entry only when this fetch was not cancelled mid-flight.
  /// A 0x8F + re-enqueue may own the slot; wiping it would strand the newer request.
  func removeQueueEntryUnlessSuperseded(_ publicKey: Data) {
    if !inFlightCancelled {
      contactFetchQueue.removeValue(forKey: publicKey)
    }
  }

  func processContactFetch(publicKey: Data) async {
    let pubKeyHex = publicKey.uppercaseHexString()

    inFlightKey = publicKey
    inFlightCancelled = false
    defer {
      if inFlightKey == publicKey {
        inFlightKey = nil
      }
    }

    let meshContact: MeshContact?
    do {
      meshContact = try await session.getContact(publicKey: publicKey)
    } catch {
      logger.error("Failed to fetch contact: \(error.localizedDescription)")
      discoverTrace.error("B2 getContact THREW key=\(pubKeyHex): \(error.localizedDescription)")
      // Drop unless a re-enqueue already owns the slot; later adverts re-enqueue.
      removeQueueEntryUnlessSuperseded(publicKey)
      return
    }

    if isCommitCancelled {
      // Task cancel without re-enqueue can leave a stale entry; stop clears the queue.
      removeQueueEntryUnlessSuperseded(publicKey)
      return
    }

    guard let meshContact else {
      discoverTrace.notice("B2 getContact returned nil key=\(pubKeyHex)")
      removeQueueEntryUnlessSuperseded(publicKey)
      return
    }

    // Re-read under the actor: advert may have upgraded a pathUpdate entry mid-flight.
    guard let entry = contactFetchQueue[publicKey] else {
      return
    }
    let reason = entry.reason
    let radioID = entry.radioID

    let frame = meshContact.toContactFrame()

    switch reason {
    case .advert:
      await commitAdvertFetch(
        publicKey: publicKey,
        pubKeyHex: pubKeyHex,
        meshContact: meshContact,
        frame: frame,
        radioID: radioID
      )
    case .pathUpdate:
      await commitPathUpdateFetch(
        publicKey: publicKey,
        pubKeyHex: pubKeyHex,
        meshContact: meshContact,
        frame: frame,
        radioID: radioID
      )
      // Advert may have upgraded the queue entry while pathUpdate save was airborne.
      if !isCommitCancelled,
         let upgraded = contactFetchQueue[publicKey],
         upgraded.reason == .advert {
        await commitAdvertFetch(
          publicKey: publicKey,
          pubKeyHex: pubKeyHex,
          meshContact: meshContact,
          frame: frame,
          radioID: upgraded.radioID
        )
      }
    }

    // After cancel, any remaining entry belongs to a newer enqueue.
    if !isCommitCancelled {
      contactFetchQueue.removeValue(forKey: publicKey)
    }
  }

  func commitAdvertFetch(
    publicKey: Data,
    pubKeyHex: String,
    meshContact: MeshContact,
    frame: ContactFrame,
    radioID: UUID
  ) async {
    if isCommitCancelled { return }

    do {
      let saveResult = try await dataStore.saveContact(radioID: radioID, from: frame)
      if isCommitCancelled {
        await rollbackInsertIfNeeded(saveResult)
        return
      }

      do {
        let (_, isNew) = try await dataStore.upsertDiscoveredNode(radioID: radioID, from: frame)
        if isCommitCancelled {
          await rollbackInsertIfNeeded(saveResult)
          return
        }
        discoverTrace.info("B2 0x80 getContact OK upsert key=\(pubKeyHex) isNew=\(isNew)")
      } catch {
        discoverTrace.error("B2 0x80 getContact-path upsert FAILED key=\(pubKeyHex): \(error.localizedDescription)")
        if isCommitCancelled {
          await rollbackInsertIfNeeded(saveResult)
          return
        }
      }

      // Empty names pass through; NotificationService supplies a localized fallback.
      let contactName = meshContact.advertisedName
      let contactType = meshContact.type
      if saveResult.isNew {
        eventBroadcaster.yield(.newContactDiscovered(
          name: contactName,
          contactID: saveResult.id,
          contactType: contactType
        ))
        logOverwriteReplacementIfRecent(
          newContactName: contactName.isEmpty ? "Unknown Contact" : contactName,
          newContactType: contactType
        )
      } else {
        // Already-synced row (deferred or repeat fetch) — UI refresh only.
        eventBroadcaster.yield(.contactUpdated)
      }
    } catch {
      logger.error("Failed to save fetched contact: \(error.localizedDescription)")
      discoverTrace.error("B2 0x80 saveContact FAILED key=\(pubKeyHex): \(error.localizedDescription)")
    }
  }

  func commitPathUpdateFetch(
    publicKey: Data,
    pubKeyHex: String,
    meshContact: MeshContact,
    frame: ContactFrame,
    radioID: UUID
  ) async {
    if isCommitCancelled { return }

    do {
      let saveResult = try await dataStore.saveContact(radioID: radioID, from: frame)
      if isCommitCancelled {
        // Insert-only rollback; never cascade-delete messages on an existing upsert.
        await rollbackInsertIfNeeded(saveResult)
        return
      }

      logger.debug("Refreshed contact path: \(meshContact.advertisedName.isEmpty ? "unnamed" : meshContact.advertisedName)")
      eventBroadcaster.yield(.contactUpdated)
    } catch {
      logger.error("Error refreshing contact path: \(error.localizedDescription)")
    }
  }

  /// Rolls back only when this fetch inserted the row. Routes through
  /// `deleteContactIfUnreferenced` so probe and delete share one store
  /// isolation region and probe errors fail closed.
  func rollbackInsertIfNeeded(_ saveResult: (id: UUID, isNew: Bool)) async {
    guard saveResult.isNew else { return }
    do {
      try await dataStore.deleteContactIfUnreferenced(id: saveResult.id)
    } catch {
      logger.warning("Insert-only rollback failed for \(saveResult.id): \(error.localizedDescription)")
    }
  }

  func resumeBarrierWaiters() {
    let waiters = barrierWaiters
    barrierWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  func cancelContactFetchWorkerAndClearQueue() {
    contactFetchWorker?.cancel()
    // Bump generation so a late worker exit cannot nil a restarted worker.
    contactFetchWorkerGeneration &+= 1
    contactFetchWorker = nil
    contactFetchQueue.removeAll()
    inFlightCancelled = true
    inFlightKey = nil
    resumeBarrierWaiters()
  }
}
